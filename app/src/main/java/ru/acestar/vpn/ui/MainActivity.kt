package ru.acestar.vpn.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import ru.acestar.vpn.R
import ru.acestar.vpn.data.repository.SessionManager
import ru.acestar.vpn.databinding.ActivityMainBinding
import ru.acestar.vpn.vpn.OpenVpnManager
import ru.acestar.vpn.vpn.VpnConnectionState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private val trafficSamples = ArrayDeque<Float>()
    private var lastTrafficBytes = 0L
    private var lastTrafficTimestamp = 0L

    private val configFilePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { loadConfigFromUri(it) }
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
        binding.btnSelectConfig.setOnClickListener {
            configFilePicker.launch("*/*")
        }

        binding.btnConnect.setOnClickListener {
            handleConnectClick()
        }

        binding.btnPause.setOnClickListener {
            viewModel.vpnManager.togglePause()
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnLogout.setOnClickListener {
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

    private fun loadConfigFromUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val success = viewModel.vpnManager.loadConfigFromInputStream(inputStream, "VPN Server")

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
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.connectionState.collectLatest { state ->
                updateConnectionUI(state)
            }
        }

        lifecycleScope.launch {
            viewModel.statusMessage.collectLatest { message ->
                if (message.isNotEmpty()) {
                    binding.tvStatusDetails.text = message
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
            combine(viewModel.byteCountIn, viewModel.byteCountOut) { inBytes, outBytes ->
                inBytes to outBytes
            }.collectLatest { (inBytes, outBytes) ->
                updateTrafficUi(inBytes, outBytes)
            }
        }

        lifecycleScope.launch {
            viewModel.isPaused.collectLatest { isPaused ->
                binding.btnPause.text = if (isPaused) {
                    getString(R.string.btn_resume)
                } else {
                    getString(R.string.btn_pause)
                }
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
                binding.btnConnect.isEnabled = viewModel.hasActiveSubscription()
                binding.btnSelectConfig.isEnabled = true
                binding.btnPause.visibility = View.GONE
                binding.loadingOverlay.visibility = View.GONE
            }

            VpnConnectionState.CONNECTING -> {
                binding.tvStatus.text = getString(R.string.status_connecting)
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.connecting))
                binding.ivVpnIcon.setColorFilter(ContextCompat.getColor(this, R.color.connecting))
                binding.btnConnect.text = getString(R.string.btn_disconnect)
                binding.btnConnect.isEnabled = true
                binding.btnSelectConfig.isEnabled = false
                binding.btnPause.visibility = View.GONE
                binding.loadingOverlay.visibility = View.GONE
            }

            VpnConnectionState.CONNECTED -> {
                binding.tvStatus.text = getString(R.string.status_connected)
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.connected))
                binding.ivVpnIcon.setColorFilter(ContextCompat.getColor(this, R.color.connected))
                binding.btnConnect.text = getString(R.string.btn_disconnect)
                binding.btnConnect.isEnabled = true
                binding.btnSelectConfig.isEnabled = false
                binding.btnPause.visibility = View.VISIBLE
                binding.loadingOverlay.visibility = View.GONE
            }

            VpnConnectionState.DISCONNECTING -> {
                binding.tvStatus.text = getString(R.string.status_disconnecting)
                binding.btnPause.visibility = View.GONE
                binding.loadingOverlay.visibility = View.VISIBLE
            }

            VpnConnectionState.ERROR -> {
                binding.tvStatus.text = getString(R.string.status_error)
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.error))
                binding.ivVpnIcon.setColorFilter(ContextCompat.getColor(this, R.color.error))
                binding.btnConnect.text = getString(R.string.btn_connect)
                binding.btnConnect.isEnabled = viewModel.hasActiveSubscription()
                binding.btnSelectConfig.isEnabled = true
                binding.btnPause.visibility = View.GONE
                binding.loadingOverlay.visibility = View.GONE
            }
        }
    }

    private fun updateTrafficUi(inBytes: Long, outBytes: Long) {
        val now = System.currentTimeMillis()
        val totalBytes = inBytes + outBytes

        if (lastTrafficTimestamp > 0L) {
            val deltaBytes = (totalBytes - lastTrafficBytes).coerceAtLeast(0L)
            val deltaTimeMs = (now - lastTrafficTimestamp).coerceAtLeast(1L)
            val speedBytesPerSec = deltaBytes * 1000f / deltaTimeMs

            if (trafficSamples.size >= 30) {
                trafficSamples.removeFirst()
            }
            trafficSamples.addLast(speedBytesPerSec)
            binding.trafficChart.setPoints(trafficSamples.toList())
        }

        val inText = Formatter.formatShortFileSize(this, inBytes)
        val outText = Formatter.formatShortFileSize(this, outBytes)
        binding.tvTrafficCurrent.text = getString(R.string.traffic_format, inText, outText)

        lastTrafficBytes = totalBytes
        lastTrafficTimestamp = now
    }
}
