package ru.acestar.vpn.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import ru.acestar.vpn.R
import ru.acestar.vpn.data.model.VpnConfigEntity
import ru.acestar.vpn.data.repository.SessionManager
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
    }

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private val trafficSamples = ArrayDeque<TrafficChartView.TrafficPoint>()
    private var lastTrafficBytes = 0L
    private var lastTrafficTimestamp = 0L
    private var isManualTrafficSession = false
    private var clearGraphOnDisconnect = false
    private var isPausedState = false
    private var currentConnectionState = VpnConnectionState.DISCONNECTED
    private var protocolReconnectPending = false
    private var selectedConfigName: String = "VPN Server"

    private val configSelectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val configId = result.data?.getLongExtra(ConfigSelectionActivity.EXTRA_SELECTED_CONFIG_ID, -1L) ?: -1L
        if (configId > 0) {
            lifecycleScope.launch {
                val config = viewModel.getConfigById(configId)
                if (config != null) {
                    selectedConfigName = config.name
                    loadConfigFromContent(config.configContent, config.name)
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
            Toast.makeText(this, R.string.protocol_applied_reconnecting, Toast.LENGTH_SHORT).show()
            reconnectVpnForProtocol()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePreferences.applyTheme(this)
        super.onCreate(savedInstanceState)

        if (!SessionManager.getInstance(this).isLoggedIn()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotificationPermissionIfNeeded()
        setupUI()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshUser()
        viewModel.vpnManager.bindService(this)
    }

    override fun onPause() {
        viewModel.vpnManager.unbindService(this)
        super.onPause()
    }

    private fun setupUI() {
        binding.btnAddConfig.setOnClickListener {
            configSelectionLauncher.launch(Intent(this, ConfigSelectionActivity::class.java))
        }

        binding.btnConfigList.setOnClickListener {
            configSelectionLauncher.launch(Intent(this, ConfigSelectionActivity::class.java))
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

        binding.btnLogout.setOnClickListener {
            stopTrafficSession(clearGraph = true)
            viewModel.vpnManager.disconnect()
            viewModel.logout()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
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
                    Toast.makeText(this, R.string.subscription_expired, Toast.LENGTH_SHORT).show()
                    return
                }
                if (!viewModel.configLoaded.value) {
                    Toast.makeText(this, R.string.no_config_selected, Toast.LENGTH_SHORT).show()
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
                        val endDate = dateFormat.format(Date(user.subscriptionEndDate))
                        binding.tvSubscription.text = getString(R.string.subscription_active, endDate)
                        binding.tvSubscription.setTextColor(
                            ContextCompat.getColor(this@MainActivity, R.color.connected)
                        )
                        binding.btnConnect.isEnabled = true
                    } else {
                        binding.tvSubscription.text = getString(R.string.subscription_expired)
                        binding.tvSubscription.setTextColor(
                            ContextCompat.getColor(this@MainActivity, R.color.error)
                        )
                        binding.btnConnect.isEnabled = false
                    }
                }
            }
        }

        lifecycleScope.launch {
            viewModel.configLoaded.collectLatest { loaded ->
                if (loaded && viewModel.connectionState.value == VpnConnectionState.DISCONNECTED) {
                    binding.tvStatusDetails.text = getString(R.string.config_loaded)
                }
            }
        }

        lifecycleScope.launch {
            viewModel.vpnConfigs.collectLatest { configs ->
                updateServerButtons(configs)
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
                binding.ivVpnIcon.setColorFilter(ContextCompat.getColor(this, R.color.disconnected))
                binding.btnConnect.text = getString(R.string.btn_connect)
                binding.btnConnect.isEnabled = viewModel.hasActiveSubscription() && viewModel.configLoaded.value
                binding.btnAddConfig.isEnabled = true
                binding.btnConfigList.isEnabled = true
                binding.loadingOverlay.visibility = View.GONE
            }

            VpnConnectionState.CONNECTING -> {
                binding.tvStatus.text = getString(R.string.status_connecting)
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.connecting))
                binding.ivVpnIcon.setColorFilter(ContextCompat.getColor(this, R.color.connecting))
                binding.btnConnect.text = getString(R.string.btn_disconnect)
                binding.btnConnect.isEnabled = true
                binding.btnAddConfig.isEnabled = false
                binding.btnConfigList.isEnabled = false
                binding.loadingOverlay.visibility = View.GONE
            }

            VpnConnectionState.CONNECTED -> {
                binding.tvStatus.text = getString(R.string.status_connected)
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.connected))
                binding.ivVpnIcon.setColorFilter(ContextCompat.getColor(this, R.color.connected))
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
                binding.ivVpnIcon.setColorFilter(ContextCompat.getColor(this, R.color.error))
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
            Toast.makeText(this, R.string.config_loaded, Toast.LENGTH_SHORT).show()
            val serverInfo = viewModel.vpnManager.getServerInfo()
            if (serverInfo != null) {
                binding.tvServerInfo.visibility = View.VISIBLE
                binding.tvServerInfo.text = "Сервер: ${serverInfo.first}:${serverInfo.second}"
            }
            binding.tvStatusDetails.text = getString(R.string.config_loaded)
        } else {
            Toast.makeText(this, "Ошибка загрузки конфигурации", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateServerButtons(configs: List<VpnConfigEntity>) {
        val serverButtons = configs.filter { it.source == "server_placeholder" }.take(2)
        if (serverButtons.isNotEmpty()) binding.btnServer1.text = serverButtons[0].name
        if (serverButtons.size > 1) binding.btnServer2.text = serverButtons[1].name
        binding.btnServer1.setOnClickListener { Toast.makeText(this, R.string.subscription_stub, Toast.LENGTH_SHORT).show() }
        binding.btnServer2.setOnClickListener { Toast.makeText(this, R.string.subscription_stub, Toast.LENGTH_SHORT).show() }
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
        startTrafficSession()
        viewModel.vpnManager.connect(this)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (!VpnSettingsPreferences.isNotificationsEnabled(this)) return
        val granted = ActivityCompat.checkSelfPermission(
            this,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1002)
        }
    }
}
