package ru.acestar.vpn.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import ru.acestar.vpn.R
import ru.acestar.vpn.VpnApplication
import ru.acestar.vpn.api.AuthApiService
import ru.acestar.vpn.data.model.VpnConfigEntity
import ru.acestar.vpn.data.database.AppDatabase
import ru.acestar.vpn.data.dao.VpnServerConfigDao
import ru.acestar.vpn.data.repository.SessionManager
import ru.acestar.vpn.data.repository.UserRepository
import ru.acestar.vpn.databinding.ActivityMainBinding
import ru.acestar.vpn.ui.ActiveVpnProtocol
import ru.acestar.vpn.vpn.WireGuardManager
import ru.acestar.vpn.vpn.OpenVpnManager
import ru.acestar.vpn.vpn.VpnConnectionState
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Locale


class MainActivity : AppCompatActivity() {

    companion object {
        private const val GRAPH_WINDOW_MS = 60 * 60 * 1000L
        const val EXTRA_GETTING_USER_DATA = "GETTING_USER_DATA"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var sessionManager: SessionManager
    private val viewModel: MainViewModel by viewModels()
    private lateinit var vpnServerConfigDao: VpnServerConfigDao
    private lateinit var authApiService: AuthApiService
    private var isServerConfigs: Boolean = false
    private var isAutoBlocked: Boolean = false
    private val userRepository by lazy {
        UserRepository((application as VpnApplication).database.userDao())
    }
    private val trafficSamples = ArrayDeque<TrafficChartView.TrafficPoint>()
    private var lastTrafficBytes = 0L
    private var lastTrafficTimestamp = 0L
    private var isManualTrafficSession = false
    private var clearGraphOnDisconnect = false
    private var isPausedState = false
    private var currentConnectionState = VpnConnectionState.DISCONNECTED
    private var protocolReconnectPending = false
    private var selectedConfigName: String = "VPN Server"
    private var selectedConfigId: Long? = null
    private var selectedConfigType: String? = null

    private var emailReminderDialog: AlertDialog? = null
    private var subscriptionReminderDialog: AlertDialog? = null
    private var emailReminderDismissedForSession = false
    private var subscriptionReminderDismissedForSession = false

    private val configSelectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val configId = result.data?.getLongExtra(ConfigSelectionActivity.EXTRA_SELECTED_CONFIG_ID, -1L) ?: -1L
        if (configId > 0) {
            lifecycleScope.launch {
                val config = viewModel.getConfigById(configId)
                if (config != null) {
                    selectedConfigId   = config.id
                    selectedConfigName = config.name
                    selectedConfigType = config.type
                    isServerConfigs    = false
                    VpnSettingsPreferences.saveSelectedConfigId(this@MainActivity, config.id)
                    // Загружаем конфиг, но НЕ подключаемся — пользователь жмёт "Подключиться"
                    // Имя для уведомления — полное пользовательское название конфига
                    loadConfigUniversal(config.configContent, config.name, config.type)
                }
            }
        }
    }

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val protocolChanged = result.data?.getBooleanExtra(SettingsActivity.EXTRA_PROTOCOL_CHANGED, false) == true
        if (protocolChanged) {
            showToast(R.string.protocol_applied_reconnecting)
            reconnectVpnForProtocol()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePreferences.applyTheme(this)
        super.onCreate(savedInstanceState)

        sessionManager = SessionManager.getInstance(this)
        authApiService = AuthApiService(sessionManager)

        // ← Сначала инициализируем UI и подписки
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        vpnServerConfigDao = AppDatabase.getDatabase(applicationContext).vpnServerConfigDao()
        setupUI()
        observeViewModel()
        isServerConfigs = VpnSettingsPreferences.getIsServerConfig(this)
        // Восстанавливаем selectedConfigId после пересоздания Activity
        val savedId = VpnSettingsPreferences.getSelectedConfigId(this)
        if (savedId > 0L) selectedConfigId = savedId

        // ← Потом запускаем корутину с авторизацией
        lifecycleScope.launch {
            val isLoggedIn = sessionManager.isLoggedIn()
            if (!isLoggedIn) {
                navigateToLogin()
                return@launch
            }

            val user = sessionManager.getCurrentUser() ?: run {
                navigateToLogin()
                return@launch
            }



            if (intent.getBooleanExtra(EXTRA_GETTING_USER_DATA, false)) {
                val result = authApiService.getUserData(
                    user.email,
                    sessionManager.getAccessToken(),
                    userRepository
                )
                if (result.isSuccess) {
                    // После успешного входа — загружаем серверные конфиги в БД
                    viewModel.syncServerConfigsFromServer()
                }
            } else {
                sessionManager.updateCurrentUser(user)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isServerConfigs = VpnSettingsPreferences.getIsServerConfig(this)
        emailReminderDismissedForSession = false
        subscriptionReminderDismissedForSession = false

        // Обновляем данные пользователя из локальной БД (быстро, без индикатора).
        // Конфиги с сервера НЕ запрашиваем — они уже в БД с момента логина.
        viewModel.refreshUser()

        if (viewModel.activeProtocol.value == ActiveVpnProtocol.OPENVPN) {
            viewModel.vpnManager.bindService(this)
            viewModel.vpnManager.ensureLoadedStateOnResume()
        }
        if (viewModel.activeProtocol.value == ActiveVpnProtocol.WIREGUARD) {
            viewModel.wireGuardManager.ensureLoadedStateOnResume()
        }
        // Если VPN подключён — убеждаемся что кнопка "Подключиться" активна
        viewModel.restoreConfigLoadedIfConnected()

        if (viewModel.connectionState.value == VpnConnectionState.CONNECTED && !isManualTrafficSession) {
            isManualTrafficSession = true
        }

        // Восстанавливаем визуальное состояние кнопок после возврата в активити
        syncButtonStates()

        // Проверка обновлений
        checkForUpdates()
    }

    /**
     * Проверяет обновления через GitHub.
     * Логика:
     *  - нет подключения VPN → обновляем сразу (тихо скачиваем и ставим)
     *  - есть подключение + приложение открыто → BottomSheet с кнопками
     *  - наступило запланированное время → ставим
     */
    private fun checkForUpdates() {
        // Обработка флага из уведомления "Позже" → сразу открываем выбор времени
        if (intent?.getBooleanExtra("open_update_schedule", false) == true) {
            intent.removeExtra("open_update_schedule")
            val info = ru.acestar.vpn.data.model.UpdateInfo(
                versionCode = intent.getIntExtra("version_code", 0),
                versionName = intent.getStringExtra("version_name") ?: "",
                apkUrl = intent.getStringExtra("apk_url") ?: "",
                apkSize = intent.getLongExtra("apk_size", 0L),
                releaseNotes = intent.getStringExtra("notes") ?: ""
            )
            if (info.apkUrl.isNotBlank()) {
                UpdateBottomSheet.newInstance(info).show(supportFragmentManager, "update")
                return
            }
        }

        lifecycleScope.launch {
            val checker = ru.acestar.vpn.update.UpdateChecker(this@MainActivity)
            val manager = ru.acestar.vpn.update.UpdateManager(this@MainActivity)

            // Если есть запланированное обновление и время наступило — ставим
            if (checker.isScheduledTimeReached()) {
                val info = checker.checkForUpdate()
                if (info != null) {
                    checker.clearSchedule()
                    manager.downloadApk(info).onSuccess { manager.installApk(it) }
                }
                return@launch
            }

            val info = checker.checkForUpdate() ?: return@launch

            // Не показываем повторно отложенную версию (если не наступило время)
            if (VpnSettingsPreferences.getPostponedVersion(this@MainActivity) == info.versionCode
                && checker.hasScheduledUpdate()
            ) return@launch

            val connected = viewModel.connectionState.value == VpnConnectionState.CONNECTED

            if (!connected) {
                // Нет активного подключения — обновляем сразу
                if (manager.canInstallPackages()) {
                    manager.downloadApk(info).onSuccess { manager.installApk(it) }
                } else {
                    // Нет разрешения — показываем диалог
                    UpdateBottomSheet.newInstance(info).show(supportFragmentManager, "update")
                }
            } else {
                // Подключение активно, приложение открыто — показываем BottomSheet
                UpdateBottomSheet.newInstance(info).show(supportFragmentManager, "update")
            }
        }
    }

    override fun onPause() {
        if (viewModel.activeProtocol.value == ActiveVpnProtocol.OPENVPN) {
            viewModel.vpnManager.unbindService(this)
        }
        super.onPause()
    }

    private fun setupUI() {
        // SwipeRefreshLayout — потянуть вниз для обновления данных пользователя
        binding.swipeRefresh.setColorSchemeColors(
            ContextCompat.getColor(this, R.color.primary),
            ContextCompat.getColor(this, R.color.connected),
            ContextCompat.getColor(this, R.color.connecting)
        )
        binding.swipeRefresh.setOnRefreshListener {
            refreshUserData()
        }

        binding.btnAddConfig.setOnClickListener {
            configSelectionLauncher.launch(
                Intent(this, ConfigSelectionActivity::class.java).apply {
                    putExtra(ConfigSelectionActivity.EXTRA_ACTIVE_CONFIG_ID, selectedConfigId ?: -1L)
                    putExtra(ConfigSelectionActivity.EXTRA_OPEN_ADD, true)
                }
            )
        }

        binding.btnConfigList.setOnClickListener {
            configSelectionLauncher.launch(
                Intent(this, ConfigSelectionActivity::class.java)
                    .apply {
                        putExtra(ConfigSelectionActivity.EXTRA_ACTIVE_CONFIG_ID, selectedConfigId ?: -1L)
                    }
            )
        }

        binding.btnConnect.setOnClickListener {
            handleConnectClick()
        }

        binding.btnPause.setOnClickListener {
            if(selectedConfigType == "OpenVPN") {
                viewModel.vpnManager.togglePause()
            }
        }

        binding.btnSettings.setOnClickListener {
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
        }

        binding.btnAccount.setOnClickListener {
            startActivity(Intent(this, AccountActivity::class.java))
        }

        binding.btnAdmin.setOnClickListener {
            // ✅ Проверяем, является ли пользователь админом
            lifecycleScope.launch {
                val user = sessionManager.getCurrentUser()
                if (user?.isAdmin == true) {
                    startActivity(Intent(this@MainActivity, AdminActivity::class.java))
                } else {
                    showToast("Недостаточно прав")
                }
            }
        }
    }

    private fun updateMainBanners(user: ru.acestar.vpn.data.model.User?) {
        if (user == null) {
            emailReminderDialog?.dismiss()
            subscriptionReminderDialog?.dismiss()
            binding.btnAdmin.visibility = View.GONE
            return
        }
        binding.btnAdmin.visibility = if (user.isAdmin) View.VISIBLE else View.GONE

        val needEmail = !user.emailConfirmed
        val needSub =
            !user.hasActiveSubscription() ||
            (user.subscriptionExpiresWithin(MainViewModel.SUBSCRIPTION_WARN_MS) && user.tariff != "Unlimited") ||
            (user.trafficUsageRatio()?.let { it >= 0.8f } == true)

        if (!needEmail) emailReminderDismissedForSession = false
        if (!needSub) subscriptionReminderDismissedForSession = false

        emailReminderDialog?.dismiss()

        if (needSub && !subscriptionReminderDismissedForSession) {
            if (subscriptionReminderDialog?.isShowing != true) {

                val msgRes = if (user.hasActiveSubscription()) {
                    R.string.banner_subscription_expires_soon
                } else {
                    R.string.banner_subscription_expiring
                }
                subscriptionReminderDialog = MaterialAlertDialogBuilder(this)
                    .setMessage(msgRes)
                    .setPositiveButton(R.string.subscription_extend) { _, _ ->
                        startActivity(Intent(this, SubscriptionActivity::class.java))
                    }
                    .setNegativeButton(R.string.banner_close) { d, _ -> d.dismiss() }
                    .setOnDismissListener {
                        subscriptionReminderDismissedForSession = true
                    }
                    .create()
                subscriptionReminderDialog?.show()
            }
        } else {
            subscriptionReminderDialog?.dismiss()
        }
    }

    private fun handleConnectClick() {
        when (viewModel.connectionState.value) {
            VpnConnectionState.CONNECTED,
            VpnConnectionState.CONNECTING -> {
                clearGraphOnDisconnect = true
                viewModel.disconnectActive()
            }

            VpnConnectionState.DISCONNECTED,
            VpnConnectionState.ERROR -> {
                if (!viewModel.hasActiveSubscription()) {
                    showToast(R.string.subscription_expired)
                    return
                }
                if (!viewModel.configLoaded.value) {
                    showToast(R.string.no_config_selected)
                    return
                }
                startTrafficSession()
                viewModel.connectActive(this)
                VpnSettingsPreferences.saveIsServerConfig(this, isServerConfigs)
            }

            VpnConnectionState.DISCONNECTING -> { /* ждём */ }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            OpenVpnManager.VPN_PERMISSION_REQUEST_CODE -> {         // 1001
                if (resultCode == RESULT_OK) viewModel.vpnManager.startVpn(this)
            }
            WireGuardManager.VPN_PERMISSION_REQUEST_CODE -> {       // 1002 — ранее терялся
                if (resultCode == RESULT_OK) viewModel.wireGuardManager.startTunnel()
            }
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.connectionState.collectLatest { state ->
                currentConnectionState = state
                if (state == VpnConnectionState.DISCONNECTED && clearGraphOnDisconnect) {
                    stopTrafficSession(clearGraph = true)
                    clearGraphOnDisconnect = false
                }
                if (state == VpnConnectionState.DISCONNECTED && protocolReconnectPending) {
                    protocolReconnectPending = false
                    reconnectVpnIfPossible()
                }
                // При восстановлении подключения после resume — помечаем конфиг загруженным
                if (state == VpnConnectionState.CONNECTED) {
                    viewModel.restoreConfigLoadedIfConnected()
                }
                updateConnectionUI(state)
                if (state == VpnConnectionState.DISCONNECTED || state == VpnConnectionState.CONNECTED) {
                    updateServerInfoText()
                    syncButtonStates()
                }
            }
        }

//        lifecycleScope.launch {
//            viewModel.statusMessage.collectLatest { message ->
//                val normalized = message.replace(",", " ").trim()
//                if (normalized.isNotEmpty() &&
//                    !(currentConnectionState == VpnConnectionState.CONNECTED && normalized.equals(getString(R.string.status_connected), ignoreCase = true))
//                ) {
//                    binding.tvStatusDetails.text = normalized
//                }
//            }
//        }

        lifecycleScope.launch {
            viewModel.currentUser.collectLatest { user ->
                // Скрываем индикатор если он был показан при автообновлении
                hideRefreshIndicator()
                if (user != null) {
                    binding.tvSubscription.visibility = View.VISIBLE
                    val tariff = ru.acestar.vpn.data.model.Tariff.fromId(user.tariff)
                    if (user.hasActiveSubscription()) {
                        val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
                        val text = when {
                            user.tariff == "Unlimited" ->
                                "${tariff.displayName} · ${getString(R.string.subscription_active_unlimited)}"
                            tariff.trafficLimitBytes != null -> {
                                val used = ru.acestar.vpn.data.model.Tariff.formatUsed(user.usedTrafficBytes)
                                val limit = ru.acestar.vpn.data.model.Tariff.formatTrafficLimit(tariff.trafficLimitBytes)
                                "${tariff.displayName} · $used / $limit"
                            }
                            user.subsEndDate != null && user.subsEndDate > 0 ->
                                "${tariff.displayName} · ${getString(R.string.subscription_active, dateFormat.format(user.subsEndDate))}"
                            else -> tariff.displayName
                        }
                        binding.tvSubscription.text = text
                        binding.tvSubscription.setTextColor(
                            ContextCompat.getColor(this@MainActivity, R.color.connected)
                        )
                        binding.btnConnect.isEnabled = viewModel.configLoaded.value || viewModel.connectionState.value == VpnConnectionState.CONNECTED
                    } else {
                        binding.tvSubscription.text = getString(R.string.subscription_expired)
                        binding.tvSubscription.setTextColor(
                            ContextCompat.getColor(this@MainActivity, R.color.error)
                        )
                        binding.btnConnect.isEnabled = false
                    }
                }
                updateMainBanners(user)
            }
        }
        lifecycleScope.launch {
            viewModel.autoModeState.collectLatest { state ->
                when (state) {
                    is MainViewModel.AutoModeState.Trying -> {
                        selectedConfigType = state.type
                        // Имя конфига не показываем — только счётчик и буква протокола
                        val protoLetter = when (state.type.lowercase()) {
                            "wireguard", "amneziawg" -> "W"
                            else -> "O"
                        }
                        updateServerButtonState("auto", ServerButtonState.CONNECTING)
                        binding.tvServerInfo.visibility = View.VISIBLE
                        binding.tvServerInfo.text = getString(
                            R.string.auto_trying,
                            state.index,
                            state.total,
                            protoLetter
                        )
                    }
                    is MainViewModel.AutoModeState.Connected -> {
                        selectedConfigType = state.config.type
                        selectedConfigName = state.config.name
                        syncButtonStates()
                        updateServerInfoText()
                    }
                    is MainViewModel.AutoModeState.AllFailed -> {
                        updateServerButtonState("auto", ServerButtonState.ERROR)
                        showToast(getString(R.string.auto_all_failed))
                        lifecycleScope.launch {
                            delay(3_000)
                            // Сбрасываем только если авто-режим уже не запущен снова
                            if (viewModel.autoModeState.value is MainViewModel.AutoModeState.AllFailed) {
                                updateServerButtonState("auto", ServerButtonState.DEFAULT)
                            }
                        }
                    }
                    is MainViewModel.AutoModeState.Idle -> {
                        updateServerButtonState("auto", ServerButtonState.DEFAULT)
                    }
                }
            }
        }

        lifecycleScope.launch {
            viewModel.configLoaded.collectLatest { loaded ->
                if (loaded && viewModel.connectionState.value == VpnConnectionState.DISCONNECTED) {
                    showToast(getString(R.string.config_loaded))
                }
                if (viewModel.connectionState.value == VpnConnectionState.DISCONNECTED ||
                    viewModel.connectionState.value == VpnConnectionState.ERROR
                ) {
                    binding.btnConnect.isEnabled = loaded && viewModel.hasActiveSubscription()
                }
                updateServerInfoText()
            }
        }

        lifecycleScope.launch {
            viewModel.vpnConfigs.collectLatest { configs ->
                updateServerButtons(configs)
                val selectedDeleted = selectedConfigId != null && configs.none { it.id == selectedConfigId }
                if (selectedDeleted) {
                    selectedConfigId = null
                    selectedConfigName = "Server"
//                    if (selectedConfigType == "OpenVPN"){
                        viewModel.clearConfigLoaded()
//                    }else if (selectedConfigType == "WireGuard") {
//
//                    }
                    binding.tvServerInfo.visibility = View.GONE
                    if (currentConnectionState == VpnConnectionState.CONNECTED ||
                        currentConnectionState == VpnConnectionState.CONNECTING ||
                        currentConnectionState == VpnConnectionState.DISCONNECTING
                    ) {
                        if(selectedConfigType == "OpenVPN"){
                            viewModel.vpnManager.disconnect()
                        }else if(selectedConfigType == "WireGuard"){
                            viewModel.wireGuardManager.disconnect()
                        }
                    }
                }
            }
        }

        lifecycleScope.launch {
            combine(viewModel.byteCountIn, viewModel.byteCountOut) { inBytes, outBytes ->
                inBytes to outBytes
            }.collectLatest { (inBytes, outBytes) ->
                updateTrafficUi(inBytes, outBytes)
            }
        }

        lifecycleScope.launch {
            viewModel.isPaused.collectLatest { isPaused ->
                isPausedState = isPaused
                binding.btnPause.text = if (isPaused) {
                    getString(R.string.btn_resume)
                } else {
                    getString(R.string.btn_pause)
                }
                refreshPauseButtonVisibility()
            }
        }

        lifecycleScope.launch {
            viewModel.trafficExhausted.collectLatest { exhausted ->
                if (exhausted) {
                    showToast(getString(R.string.notif_traffic_exhausted_title))
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(R.string.notif_traffic_exhausted_title)
                        .setMessage(R.string.notif_traffic_exhausted_text)
                        .setPositiveButton(R.string.subscription_extend) { _, _ ->
                            startActivity(Intent(this@MainActivity, SubscriptionActivity::class.java))
                        }
                        .setNegativeButton(R.string.banner_close, null)
                        .show()
                }
            }
        }

        // Блокировка кнопки Авто при исчерпании трафика/срока тарифа
        lifecycleScope.launch {
            viewModel.autoBlocked.collectLatest { blocked ->
                isAutoBlocked = blocked
                applyAutoButtonBlockState(blocked)
            }
        }
    }

    /** Применяет визуальную блокировку к кнопке Авто */
    private fun applyAutoButtonBlockState(blocked: Boolean) {
        val autoButton = findServerButton("auto") ?: return
        autoButton.isEnabled = !blocked
        autoButton.alpha = if (blocked) 0.4f else 1f
    }

    private fun updateConnectionUI(state: VpnConnectionState) {
        when (state) {
            VpnConnectionState.DISCONNECTED -> {
                binding.tvStatus.text = getString(R.string.status_disconnected)
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.disconnected))
                binding.ivVpnIcon.setImageResource(R.mipmap.ic_vpn_gray_foreground)
                binding.btnConnect.text = getString(R.string.btn_connect)
                binding.btnConnect.isEnabled = viewModel.hasActiveSubscription() && viewModel.configLoaded.value
                binding.btnAddConfig.isEnabled = true
                binding.btnConfigList.isEnabled = true
                binding.loadingOverlay.visibility = View.GONE
            }

            VpnConnectionState.CONNECTING -> {
                binding.tvStatus.text = getString(R.string.status_connecting)
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.connecting))
                binding.ivVpnIcon.setImageResource(R.mipmap.ic_vpn_orange_foreground)
                binding.btnConnect.text = getString(R.string.btn_disconnect)
                binding.btnConnect.isEnabled = true
                binding.btnAddConfig.isEnabled = false
                binding.btnConfigList.isEnabled = false
                binding.loadingOverlay.visibility = View.GONE
            }

            VpnConnectionState.CONNECTED -> {
                binding.tvStatus.text = getString(R.string.status_connected)
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.connected))
                binding.ivVpnIcon.setImageResource(R.mipmap.ic_vpn_green_foreground)
                binding.btnConnect.text = getString(R.string.btn_disconnect)
                binding.btnConnect.isEnabled = true
                binding.btnAddConfig.isEnabled = false
                binding.btnConfigList.isEnabled = false
                binding.loadingOverlay.visibility = View.GONE
            }

            VpnConnectionState.DISCONNECTING -> {
                binding.tvStatus.text = getString(R.string.status_disconnecting)
                binding.loadingOverlay.visibility = View.GONE
            }

            VpnConnectionState.ERROR -> {
                binding.tvStatus.text = getString(R.string.status_error)
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.error))
                binding.ivVpnIcon.setImageResource(R.mipmap.ic_vpn_red_foreground)
                binding.btnConnect.text = getString(R.string.btn_connect)
                binding.btnConnect.isEnabled = viewModel.hasActiveSubscription() && viewModel.configLoaded.value
                binding.btnAddConfig.isEnabled = true
                binding.btnConfigList.isEnabled = true
                binding.loadingOverlay.visibility = View.GONE
            }
        }
        refreshPauseButtonVisibility()
    }

    private fun updateTrafficUi(inBytes: Long, outBytes: Long) {
        val now = System.currentTimeMillis()
        val totalBytes = inBytes + outBytes

        if (!isManualTrafficSession) {
            lastTrafficBytes = totalBytes
            lastTrafficTimestamp = now
            return
        }

        if (lastTrafficTimestamp > 0L) {
            val deltaBytes = (totalBytes - lastTrafficBytes).coerceAtLeast(0L)
            val deltaTimeMs = (now - lastTrafficTimestamp).coerceAtLeast(1L)
            val speedBytesPerSec = deltaBytes * 1000f / deltaTimeMs

            trafficSamples.addLast(TrafficChartView.TrafficPoint(now, speedBytesPerSec))
            pruneOldTrafficPoints(now)
            binding.trafficChart.setPoints(trafficSamples.toList())
        }

        val inText = Formatter.formatShortFileSize(this, inBytes)
        val outText = Formatter.formatShortFileSize(this, outBytes)
        binding.tvTrafficCurrent.text = getString(R.string.traffic_format, inText, outText)

        lastTrafficBytes = totalBytes
        lastTrafficTimestamp = now
    }

    private fun startTrafficSession() {
        isManualTrafficSession = true
        clearGraphOnDisconnect = false
        trafficSamples.clear()
        binding.trafficChart.setPoints(emptyList())
        binding.tvTrafficCurrent.text = getString(R.string.traffic_default)
        lastTrafficTimestamp = 0L
        lastTrafficBytes = 0L
    }

    private fun stopTrafficSession(clearGraph: Boolean) {
        isManualTrafficSession = false
        lastTrafficTimestamp = 0L
        lastTrafficBytes = 0L
        if (clearGraph) {
            trafficSamples.clear()
            binding.trafficChart.setPoints(emptyList())
            binding.tvTrafficCurrent.text = getString(R.string.traffic_default)
        }
    }

    private fun pruneOldTrafficPoints(now: Long) {
        while (trafficSamples.isNotEmpty() && now - trafficSamples.first().timestampMs > GRAPH_WINDOW_MS) {
            trafficSamples.removeFirst()
        }
    }
    private fun reconnectVpnForProtocol() {
        if (selectedConfigType != "OpenVPN") return
        if (!viewModel.vpnManager.hasConfig()) return

        viewModel.vpnManager.reloadCurrentConfigWithProtocol(selectedConfigName)

        when (currentConnectionState) {
            VpnConnectionState.CONNECTED,
            VpnConnectionState.CONNECTING,
            VpnConnectionState.DISCONNECTING -> {
                protocolReconnectPending = true
                viewModel.vpnManager.disconnect()
            }
            VpnConnectionState.DISCONNECTED,
            VpnConnectionState.ERROR -> reconnectVpnIfPossible()
        }
    }

    private fun refreshPauseButtonVisibility() {
        val showPauseButton = currentConnectionState == VpnConnectionState.CONNECTED || isPausedState
        binding.btnPause.visibility = if (showPauseButton) View.VISIBLE else View.GONE
    }

    private fun loadConfigFromContent(content: String, serverName: String) {
        selectedConfigName = serverName
        val success = viewModel.vpnManager.loadConfigFromInputStream(
            ByteArrayInputStream(content.toByteArray()),
            serverName
        )
        if (success) {
            if (selectedConfigType == "OpenVPN"){
                viewModel.onConfigLoaded()
            }else if (selectedConfigType == "WireGuard"){

            }

//            showToast(R.string.config_loaded)
            updateServerInfoText()
            showToast(getString(R.string.config_loaded))
        } else {
            showToast("Ошибка загрузки конфигурации")
        }
    }
    private fun loadConfigUniversal(content: String, name: String, type: String) {
        selectedConfigName = name
        selectedConfigType = type

        val success = viewModel.loadUserConfig(content, name, type)
        if (success) {
            viewModel.onConfigLoaded()
            updateServerInfoText()
            showToast(getString(R.string.config_loaded))
        } else {
            showToast("Ошибка загрузки конфигурации")
        }
    }
//    private fun waitLoadServerConfigs(){
//        lifecycleScope.launch {
//            val result = viewModel.loadServerConfigsAndWait(1)
//
//            if (result) {
//                // Конфиги загружены, подключаемся
//                updateServerInfoText()
//                viewModel.onConfigLoaded()
//
//                when (currentConnectionState) {
//                    VpnConnectionState.CONNECTED,
//                    VpnConnectionState.CONNECTING,
//                    VpnConnectionState.DISCONNECTING -> {
//                        protocolReconnectPending = true
//                        viewModel.vpnManager.disconnect()
//                    }
//                    VpnConnectionState.DISCONNECTED,
//                    VpnConnectionState.ERROR -> {
//                        reconnectVpnIfPossible()
//                    }
//                }
//            } else {
//                delay(500)
//                waitLoadServerConfigs()
//            }
//        }
//    }

    private fun updateServerButtons(configs: List<VpnConfigEntity>) {
        binding.layoutServerButtons.removeAllViews()
        val serverButtons = configs.filter { it.source == "server_placeholder" }.take(2)

        val buttons = mutableListOf<MaterialButton>()


        buttons += createServerButton().apply {
            tag  = "auto"
            text = "Авто"
            isEnabled = !isAutoBlocked
            alpha = if (isAutoBlocked) 0.4f else 1f
            setOnClickListener {
                // Блокировка при исчерпании трафика/срока
                if (isAutoBlocked) {
                    showErrorDialog(viewModel.autoBlockReason.value.ifBlank {
                        getString(R.string.notif_traffic_exhausted_text)
                    })
                    return@setOnClickListener
                }
                updateAllServerButtonsToDefault()
                updateServerButtonState("auto", ServerButtonState.ACTIVE)
                VpnSettingsPreferences.saveIsServerConfig(this@MainActivity, true)
                isServerConfigs    = true
                selectedConfigType = null // авто-режим, тип неизвестен заранее
                selectedConfigId   = null
                VpnSettingsPreferences.saveSelectedConfigId(this@MainActivity, -1L)

                if (viewModel.hasActiveSubscription()) {
                    // Отключаем текущее если было, потом запускаем авто-перебор
                    if (currentConnectionState == VpnConnectionState.CONNECTED ||
                        currentConnectionState == VpnConnectionState.CONNECTING
                    ) {
                        viewModel.disconnectActive()
                        // Небольшая задержка, потом запускаем авто
                        lifecycleScope.launch {
                            delay(800)
                            startTrafficSession()
                            viewModel.startAutoMode(this@MainActivity)
                        }
                    } else {
                        startTrafficSession()
                        viewModel.startAutoMode(this@MainActivity)
                    }
                } else {
                    showErrorDialog("Срок действия подписки истек")
                }
            }
        }
//        buttons += createServerButton().apply {
//            text = if (serverButtons.size > 1) serverButtons[1].name else getString(R.string.server_2)
//            setOnClickListener {
//                viewModel.tryConnectNextConfig()
//                showToast(R.string.subscription_stub)
//            }
//        }

        val favoriteConfigs = configs
            .filter { it.isFavorite && it.configContent.isNotBlank() }
            .take(10)

        favoriteConfigs.forEach { favorite ->
            val protocol = extractProtocolLabel(favorite.configContent)
            buttons += createServerButton().apply {
                tag = favorite.id
                // Имя кнопки = пользовательское название конфига (переименованное)
                text = "${favorite.name} $protocol"

                setOnClickListener {
                    updateAllServerButtonsToDefault()
                    updateServerButtonState(favorite.id, ServerButtonState.ACTIVE)
                    VpnSettingsPreferences.saveIsServerConfig(this@MainActivity, false)
                    isServerConfigs    = false
                    selectedConfigId   = favorite.id
                    selectedConfigType = favorite.type
                    VpnSettingsPreferences.saveSelectedConfigId(this@MainActivity, favorite.id)

                    // Имя для уведомления — полное пользовательское название
                    loadConfigUniversal(favorite.configContent, favorite.name, favorite.type)

                    when (currentConnectionState) {
                        VpnConnectionState.CONNECTED,
                        VpnConnectionState.CONNECTING,
                        VpnConnectionState.DISCONNECTING -> {
                            protocolReconnectPending = true
                            viewModel.disconnectActive()
                        }
                        VpnConnectionState.DISCONNECTED,
                        VpnConnectionState.ERROR -> reconnectVpnIfPossible()
                    }
                }
            }
        }

        val maxPerRow = 3
        buttons.chunked(maxPerRow).forEach { rowButtons ->
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (6 * resources.displayMetrics.density).toInt()
                }
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
            rowButtons.forEachIndexed { index, button ->
                val params = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                if (index > 0) params.marginStart = (8 * resources.displayMetrics.density).toInt()
                row.addView(button, params)
            }
            binding.layoutServerButtons.addView(row)
        }

        // Кнопки пересозданы — восстанавливаем актуальное визуальное состояние
        syncButtonStates()
        // Восстанавливаем блокировку кнопки Авто
        applyAutoButtonBlockState(isAutoBlocked)
    }

    /**
     * Синхронизирует визуальное состояние кнопок серверов с текущим состоянием подключения.
     * Вызывается при: смене connectionState, onResume, пересоздании кнопок (updateServerButtons).
     *
     * Логика:
     * - Если авто-режим активен и идёт перебор → кнопка "Авто" уже управляется из autoModeState observer
     * - Если подключено через авто → кнопка "Авто" = CONNECTED, остальные DEFAULT
     * - Если подключено через избранное → нужная кнопка = CONNECTED, остальные DEFAULT
     * - Если отключено → все DEFAULT (кроме случая когда авто-режим в состоянии Trying/AllFailed)
     */
    private fun syncButtonStates() {
        val connState = viewModel.connectionState.value
        val autoState = viewModel.autoModeState.value

        // Если авто-режим в процессе перебора — не трогаем, им управляет autoModeState observer
        if (autoState is MainViewModel.AutoModeState.Trying) return

        updateAllServerButtonsToDefault()

        when {
            // Авто-режим подключён
            autoState is MainViewModel.AutoModeState.Connected &&
            (connState == VpnConnectionState.CONNECTED || connState == VpnConnectionState.CONNECTING) -> {
                updateServerButtonState("auto", ServerButtonState.CONNECTED)
            }

            // Авто-режим был активен (isServerConfigs) и мы подключены
            isServerConfigs &&
            (connState == VpnConnectionState.CONNECTED || connState == VpnConnectionState.CONNECTING) -> {
                updateServerButtonState("auto", ServerButtonState.CONNECTED)
            }

            // Подключено через избранное
            !isServerConfigs &&
            selectedConfigId != null &&
            (connState == VpnConnectionState.CONNECTED || connState == VpnConnectionState.CONNECTING) -> {
                updateServerButtonState(selectedConfigId!!, ServerButtonState.CONNECTED)
            }

            // Всё остальное — DEFAULT (уже сброшено выше)
        }
    }

    /**
     * Обновляет данные пользователя с сервера.
     * Вызывается при свайпе вниз или автоматически при изменении данных.
     * По завершении скрывает индикатор SwipeRefresh.
     */
    private fun refreshUserData() {
        showRefreshIndicatorWithTimeout(timeoutMs = 10_000L) // для ручного свайпа даём 10 сек
        lifecycleScope.launch {
            try {
                val user = sessionManager.getCurrentUser() ?: run {
                    hideRefreshIndicator()
                    return@launch
                }
                val result = authApiService.getUserData(
                    user.email,
                    sessionManager.getAccessToken(),
                    userRepository
                )
                if (result.isSuccess) {
                    viewModel.refreshUser()
                } else {
                    showToast("Не удалось обновить данные")
                }
            } catch (e: Exception) {
                showToast("Ошибка обновления: ${e.message}")
            } finally {
                hideRefreshIndicator()
            }
        }
    }

    /**
     * Показывает индикатор обновления с автоматическим таймаутом.
     * Гарантированно скрывается через [timeoutMs] даже если данные не пришли.
     * Повторный вызов сбрасывает таймер.
     */
    private var refreshTimeoutJob: kotlinx.coroutines.Job? = null
    private val REFRESH_TIMEOUT_MS = 3_000L

    private fun showRefreshIndicatorWithTimeout(timeoutMs: Long = REFRESH_TIMEOUT_MS) {
        binding.swipeRefresh.isRefreshing = true
        refreshTimeoutJob?.cancel()
        refreshTimeoutJob = lifecycleScope.launch {
            delay(timeoutMs)
            binding.swipeRefresh.isRefreshing = false
        }
    }

    private fun showRefreshIndicator() {
        showRefreshIndicatorWithTimeout()
    }

    private fun hideRefreshIndicator() {
        refreshTimeoutJob?.cancel()
        refreshTimeoutJob = null
        binding.swipeRefresh.isRefreshing = false
    }

    private fun reconnectVpnIfPossible() {
        if (!viewModel.hasActiveSubscription()) return
        if (!viewModel.configLoaded.value) return
        if (!isManualTrafficSession) startTrafficSession()
        viewModel.connectActive(this)
    }



    private fun updateServerInfoText() {
        isServerConfigs = VpnSettingsPreferences.getIsServerConfig(this)
        when {
            isServerConfigs -> {
                binding.tvServerInfo.visibility = View.VISIBLE
                binding.tvServerInfo.text = getString(R.string.server_info_auto)
            }
            selectedConfigType == "WireGuard" -> {
                binding.tvServerInfo.visibility = View.VISIBLE
                // У WireGuard нет host/port в конфиге в том же формате
                binding.tvServerInfo.text = getString(
                    R.string.server_info_with_protocol,
                    selectedConfigName, "", "WireGuard"
                )
            }
            selectedConfigType == "OpenVPN" -> {
                val serverInfo = viewModel.vpnManager.getServerInfo() ?: run {
                    binding.tvServerInfo.visibility = View.GONE
                    return
                }
                val protocol = viewModel.vpnManager.getCurrentProtocolLabel()
                binding.tvServerInfo.visibility = View.VISIBLE
                binding.tvServerInfo.text = getString(
                    R.string.server_info_with_protocol,
                    serverInfo.first, serverInfo.second, protocol
                )
            }
            else -> binding.tvServerInfo.visibility = View.GONE
        }
    }

    private fun showToast(messageRes: Int) {
        if (!VpnSettingsPreferences.isToastNotificationsEnabled(this)) return
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
    }

    private fun showToast(message: String) {
        if (!VpnSettingsPreferences.isToastNotificationsEnabled(this)) return
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun extractProtocolLabel(configContent: String): String {
        configContent.lines().forEach { line ->
            val trimmed = line.trim().lowercase()
            if (trimmed.startsWith("proto ")) {
                return when {
                    trimmed.contains("tcp") -> "TCP"
                    trimmed.contains("udp") -> "UDP"
                    else -> "AUTO"
                }
            }
        }
        return "AUTO"
    }

    private fun shortHostLabel(host: String): String {
        val parts = host.split(".")
        if (parts.size >= 2) return "${parts[0]}.${parts[1]}"
        return host.take(10)
    }

    private fun createServerButton(): MaterialButton {
        return MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            minHeight = (42 * resources.displayMetrics.density).toInt()
            minimumHeight = (42 * resources.displayMetrics.density).toInt()
            setPadding(
                (14 * resources.displayMetrics.density).toInt(),
                (10 * resources.displayMetrics.density).toInt(),
                (14 * resources.displayMetrics.density).toInt(),
                (10 * resources.displayMetrics.density).toInt()
            )
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            textSize = 14f
            applyButtonPressAnimation()
        }
    }

    /**
     * Навешивает анимацию нажатия (scale down) и удерживания (pulse) на кнопку.
     * - ACTION_DOWN: плавно уменьшает до 0.93 за 100мс
     * - ACTION_UP / ACTION_CANCEL: возвращает к 1.0 за 150мс
     * Удерживание имитируется через повторяющийся pulse-аниматор,
     * который запускается после 300мс зажатия.
     */
    private fun MaterialButton.applyButtonPressAnimation() {
        var holdAnimator: AnimatorSet? = null
        val holdDelayMs = 300L

        val holdRunnable = Runnable {
            holdAnimator?.cancel()
            holdAnimator = AnimatorSet().apply {
                val scaleXPulse = ObjectAnimator.ofFloat(this@applyButtonPressAnimation, "scaleX", 0.93f, 0.88f, 0.93f)
                val scaleYPulse = ObjectAnimator.ofFloat(this@applyButtonPressAnimation, "scaleY", 0.93f, 0.88f, 0.93f)
                scaleXPulse.repeatCount = ObjectAnimator.INFINITE
                scaleYPulse.repeatCount = ObjectAnimator.INFINITE
                scaleXPulse.duration = 400
                scaleYPulse.duration = 400
                playTogether(scaleXPulse, scaleYPulse)
                start()
            }
        }

        setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    holdAnimator?.cancel()
                    // Анимация нажатия — уменьшение
                    AnimatorSet().apply {
                        val sx = ObjectAnimator.ofFloat(v, "scaleX", 1f, 0.93f)
                        val sy = ObjectAnimator.ofFloat(v, "scaleY", 1f, 0.93f)
                        duration = 100
                        playTogether(sx, sy)
                        start()
                    }
                    // Запускаем pulse если удерживают
                    v.postDelayed(holdRunnable, holdDelayMs)
                    false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(holdRunnable)
                    holdAnimator?.cancel()
                    holdAnimator = null
                    // Анимация отпускания — возврат к исходному размеру
                    AnimatorSet().apply {
                        val sx = ObjectAnimator.ofFloat(v, "scaleX", v.scaleX, 1f)
                        val sy = ObjectAnimator.ofFloat(v, "scaleY", v.scaleY, 1f)
                        duration = 150
                        playTogether(sx, sy)
                        start()
                    }
                    false
                }
                else -> false
            }
        }
    }

    /**
     * Ищет кнопку сервера в layoutServerButtons по тегу (configId или "auto")
     * и обновляет её визуальное состояние.
     *
     * @param buttonTag  Long (configId) или String "auto"
     * @param state      ServerButtonState — определяет цвет фона и обводки
     */
    fun updateServerButtonState(buttonTag: Any, state: ServerButtonState) {
        val button = findServerButton(buttonTag) ?: return
        button.applyServerButtonState(state)
    }

    /**
     * Сбрасывает визуальное состояние всех кнопок серверов в DEFAULT.
     * Вызывается перед тем, как выделить новую активную кнопку.
     */
    private fun updateAllServerButtonsToDefault() {
        val container = binding.layoutServerButtons
        for (i in 0 until container.childCount) {
            val row = container.getChildAt(i)
            if (row is ViewGroup) {
                for (j in 0 until row.childCount) {
                    val child = row.getChildAt(j)
                    if (child is MaterialButton) child.applyServerButtonState(ServerButtonState.DEFAULT)
                }
            }
        }
    }

    /**
     * Рекурсивно ищет MaterialButton по тегу внутри layoutServerButtons.
     */
    private fun findServerButton(buttonTag: Any): MaterialButton? {
        val container = binding.layoutServerButtons
        for (i in 0 until container.childCount) {
            val row = container.getChildAt(i)
            if (row is ViewGroup) {
                for (j in 0 until row.childCount) {
                    val child = row.getChildAt(j)
                    if (child is MaterialButton && child.tag == buttonTag) return child
                }
            }
        }
        return null
    }

    /**
     * Применяет визуальное состояние к кнопке сервера.
     */
    private fun MaterialButton.applyServerButtonState(state: ServerButtonState) {
        when (state) {
            ServerButtonState.DEFAULT -> {
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.TRANSPARENT
                )
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                strokeWidth = (1 * resources.displayMetrics.density).toInt()
                strokeColor = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this@MainActivity, R.color.disconnected)
                )
                alpha = 1f
            }
            ServerButtonState.ACTIVE -> {
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this@MainActivity, R.color.server_button_selected)
                )
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                strokeWidth = (2 * resources.displayMetrics.density).toInt()
                strokeColor = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this@MainActivity, R.color.primary)
                )
                alpha = 1f
            }
            ServerButtonState.CONNECTING -> {
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this@MainActivity, R.color.connecting)
                )
                setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.white))
                strokeWidth = (2 * resources.displayMetrics.density).toInt()
                strokeColor = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this@MainActivity, R.color.connecting)
                )
                alpha = 1f
            }
            ServerButtonState.CONNECTED -> {
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this@MainActivity, R.color.primary)
                )
                setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.white))
                strokeWidth = (2 * resources.displayMetrics.density).toInt()
                strokeColor = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this@MainActivity, R.color.primary_dark)
                )
                alpha = 1f
            }
            ServerButtonState.ERROR -> {
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this@MainActivity, R.color.error)
                )
                setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.white))
                strokeWidth = (2 * resources.displayMetrics.density).toInt()
                strokeColor = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this@MainActivity, R.color.error)
                )
                alpha = 1f
            }
            ServerButtonState.DISABLED -> {
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this@MainActivity, R.color.error)
                )
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                strokeWidth = (1 * resources.displayMetrics.density).toInt()
                strokeColor = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this@MainActivity, R.color.disconnected)
                )
                alpha = 0.4f
            }
        }
    }

    private fun showErrorDialog(message: String) {
        if (isFinishing || isDestroyed) {
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Ошибка")
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .show()
    }
    private fun showSuccessDialog(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Успех")
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    /**
     * Возможные визуальные состояния кнопки сервера.
     * Используется в updateServerButtonState() для динамической смены фона.
     */
    enum class ServerButtonState {
        /** Обычное состояние — прозрачный фон, серая обводка */
        DEFAULT,
        /** Выбранный сервер — акцентный фон, цветная обводка */
        ACTIVE,
        /** Идёт подключение к этому серверу */
        CONNECTING,
        /** Подключено через этот сервер */
        CONNECTED,
        /** Ошибка подключения к этому серверу */
        ERROR,
        /** Недоступна (нет подписки и т.п.) */
        DISABLED
    }

    private fun navigateToLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }
}