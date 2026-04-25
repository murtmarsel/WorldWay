package ru.acestar.vpn.ui

import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.remote.creation.first
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
import ru.acestar.vpn.vpn.OpenVpnManager
import ru.acestar.vpn.vpn.VpnConnectionState
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
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
                val shortHost = shortHostLabel(config?.serverHost ?: "")
                if (config != null) {
                    selectedConfigId = config.id
                    selectedConfigName = config.name
                    loadConfigFromContent(config.configContent, shortHost)
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

        lifecycleScope.launch {
            val isLoggedIn = sessionManager.isLoggedIn()
            if (isLoggedIn) {
                val user = sessionManager.getCurrentUser()
                println("EXTRA_GETTING_USER_DATA: ${intent.getBooleanExtra(EXTRA_GETTING_USER_DATA, false)}")
                if(intent.getBooleanExtra(EXTRA_GETTING_USER_DATA, false)){
                    authApiService.getUserData(user!!.email, sessionManager.getAccessToken(), userRepository)
                }




                user?.let { sessionManager.updateCurrentUser(it) }
            } else {
                navigateToLogin()
                return@launch
            }
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        vpnServerConfigDao = AppDatabase.getDatabase(applicationContext).vpnServerConfigDao()

        setupUI()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        emailReminderDismissedForSession = false
        subscriptionReminderDismissedForSession = false
        viewModel.refreshUser()
        viewModel.vpnManager.bindService(this)
        viewModel.vpnManager.ensureLoadedStateOnResume()
        if (viewModel.connectionState.value == VpnConnectionState.CONNECTED && !isManualTrafficSession) {
            isManualTrafficSession = true
        }
    }

    override fun onPause() {
        viewModel.vpnManager.unbindService(this)
        super.onPause()
    }

    private fun setupUI() {
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
            viewModel.vpnManager.togglePause()
        }

        binding.btnSettings.setOnClickListener {
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
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
            !user.hasActiveSubscription() || user.subscriptionExpiresWithin(MainViewModel.SUBSCRIPTION_WARN_MS)

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
                viewModel.vpnManager.disconnect()
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
                viewModel.vpnManager.connect(this)
            }

            VpnConnectionState.DISCONNECTING -> {}
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OpenVpnManager.VPN_PERMISSION_REQUEST_CODE && resultCode == RESULT_OK) {
            viewModel.vpnManager.startVpn(this)
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
                updateConnectionUI(state)
                if (state == VpnConnectionState.DISCONNECTED || state == VpnConnectionState.CONNECTED) {
                    updateServerInfoText(false)
                }
            }
        }

        lifecycleScope.launch {
            viewModel.statusMessage.collectLatest { message ->
                val normalized = message.replace(",", " ").trim()
                if (normalized.isNotEmpty() &&
                    !(currentConnectionState == VpnConnectionState.CONNECTED && normalized.equals(getString(R.string.status_connected), ignoreCase = true))
                ) {
                    binding.tvStatusDetails.text = normalized
                }
            }
        }

        lifecycleScope.launch {
            viewModel.currentUser.collectLatest { user ->
                if (user != null) {
                    binding.tvSubscription.visibility = View.VISIBLE
                    if (user.hasActiveSubscription()) {
                        val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
                        var endDate = dateFormat.format(user.subsEndDate)
//                        println("Тариф пользователя: ${user.tariff}")
                        if (user.tariff == "Unlimited") endDate = getString(R.string.subscription_active_unlimited)
                        binding.tvSubscription.text = getString(R.string.subscription_active, endDate)
                        binding.tvSubscription.setTextColor(
                            ContextCompat.getColor(this@MainActivity, R.color.connected)
                        )
                        binding.btnConnect.isEnabled = viewModel.configLoaded.value
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
            viewModel.configLoaded.collectLatest { loaded ->
                if (loaded && viewModel.connectionState.value == VpnConnectionState.DISCONNECTED) {
                    binding.tvStatusDetails.text = getString(R.string.config_loaded)
                }
                if (viewModel.connectionState.value == VpnConnectionState.DISCONNECTED ||
                    viewModel.connectionState.value == VpnConnectionState.ERROR
                ) {
                    binding.btnConnect.isEnabled = loaded && viewModel.hasActiveSubscription()
                }
                updateServerInfoText(false)
            }
        }

        lifecycleScope.launch {
            viewModel.vpnConfigs.collectLatest { configs ->
                updateServerButtons(configs)
                val selectedDeleted = selectedConfigId != null && configs.none { it.id == selectedConfigId }
                if (selectedDeleted) {
                    selectedConfigId = null
                    selectedConfigName = "Server"
                    viewModel.clearConfigLoaded()
                    binding.tvServerInfo.visibility = View.GONE
                    binding.tvStatusDetails.text = getString(R.string.no_config_selected)
                    if (currentConnectionState == VpnConnectionState.CONNECTED ||
                        currentConnectionState == VpnConnectionState.CONNECTING ||
                        currentConnectionState == VpnConnectionState.DISCONNECTING
                    ) {
                        viewModel.vpnManager.disconnect()
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
                binding.tvStatusDetails.text = ""
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
            viewModel.onConfigLoaded()
//            showToast(R.string.config_loaded)
            updateServerInfoText(false)
            binding.tvStatusDetails.text = getString(R.string.config_loaded)
        } else {
            showToast("Ошибка загрузки конфигурации")
        }
    }
    private fun waitLoadServerConfigs(){
        lifecycleScope.launch {
            val result = viewModel.loadServerConfigsAndWait(1)

            if (result) {
                // Конфиги загружены, подключаемся
                updateServerInfoText(true)
                viewModel.onConfigLoaded()

                when (currentConnectionState) {
                    VpnConnectionState.CONNECTED,
                    VpnConnectionState.CONNECTING,
                    VpnConnectionState.DISCONNECTING -> {
                        protocolReconnectPending = true
                        viewModel.vpnManager.disconnect()
                    }
                    VpnConnectionState.DISCONNECTED,
                    VpnConnectionState.ERROR -> {
                        reconnectVpnIfPossible()
                    }
                }
            } else {
                delay(500)
                waitLoadServerConfigs()
            }
        }
    }

    private fun updateServerButtons(configs: List<VpnConfigEntity>) {
        binding.layoutServerButtons.removeAllViews()
        val serverButtons = configs.filter { it.source == "server_placeholder" }.take(2)

        val buttons = mutableListOf<MaterialButton>()


        buttons += createServerButton().apply {
            setOnClickListener {
                if(viewModel.hasActiveSubscription()){
                    waitLoadServerConfigs()
                }else{
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
            val shortHost = shortHostLabel(favorite.serverHost)
            buttons += createServerButton().apply {
                text = "$shortHost $protocol"


                var isActive: Boolean;

                if (favorite.id == selectedConfigId) isActive = true
                else isActive = false


                setOnClickListener {
                    applyQuickButtonState(isActive)
//                    showToast("fav id: ${favorite.id}, sel: ${selectedConfigId}")
                    selectedConfigId = favorite.id
                    loadConfigFromContent(favorite.configContent, shortHost)
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
    }

    private fun reconnectVpnForProtocol() {
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
            VpnConnectionState.ERROR -> {
                reconnectVpnIfPossible()
            }
        }
    }

    private fun reconnectVpnIfPossible() {
        if (!viewModel.hasActiveSubscription()) return
        if (!viewModel.configLoaded.value) return
        if (!isManualTrafficSession) startTrafficSession()
        viewModel.vpnManager.connect(this)
    }

    private fun updateServerInfoText(isServer: Boolean) {
        val serverInfo = viewModel.vpnManager.getServerInfo() ?: return
        val protocol = viewModel.vpnManager.getCurrentProtocolLabel()
        binding.tvServerInfo.visibility = View.VISIBLE
        if(isServer){
            binding.tvServerInfo.text = getString(R.string.server_info_auto)
        }
        else{
            binding.tvServerInfo.text = getString(
                R.string.server_info_with_protocol,
                serverInfo.first,
                serverInfo.second,
                protocol
            )
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
        }
    }

    private fun MaterialButton.applyQuickButtonState(isActive: Boolean) {
        if (isActive) {
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.server_button_selected))
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            strokeWidth = (2 * resources.displayMetrics.density).toInt()
            strokeColor = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this@MainActivity, R.color.primary)
            )
        } else {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            strokeWidth = (1 * resources.displayMetrics.density).toInt()
            strokeColor = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this@MainActivity, R.color.disconnected)
            )
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

    private fun navigateToLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }
}