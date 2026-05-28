package ru.acestar.vpn.vpn

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.IOpenVPNServiceInternal
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VPNLaunchHelper
import de.blinkt.openvpn.core.VpnStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.acestar.vpn.ui.VpnSettingsPreferences
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.StringReader

class OpenVpnManager(private val context: Context) : VpnStatus.StateListener, VpnStatus.ByteCountListener, VpnManagerInterface {
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var forceDisconnectJob: Job? = null
    private val runtimePrefs = context.getSharedPreferences("vpn_runtime_state", Context.MODE_PRIVATE)

    private val _connectionState = MutableStateFlow(VpnConnectionState.DISCONNECTED)
    override  val connectionState: StateFlow<VpnConnectionState> = _connectionState.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    override  val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _byteCountIn = MutableStateFlow(0L)
    override  val byteCountIn: StateFlow<Long> = _byteCountIn.asStateFlow()

    private val _byteCountOut = MutableStateFlow(0L)
    override  val byteCountOut: StateFlow<Long> = _byteCountOut.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    override  val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private var rawConfigContent: String? = null
    private var currentConfigContent: String? = null
    private var vpnProfile: VpnProfile? = null
    private var mService: IOpenVPNServiceInternal? = null
    private var isBound = false

    companion object {
        private const val TAG = "OpenVpnManager"
        const val VPN_PERMISSION_REQUEST_CODE = 1001
        private const val KEY_LAST_STATE = "last_state"
        private const val KEY_LAST_STATUS = "last_status"
        private const val KEY_LAST_TS = "last_ts"

        @Volatile
        private var INSTANCE: OpenVpnManager? = null

        fun getInstance(context: Context): OpenVpnManager {
            return INSTANCE ?: synchronized(this) {
                val instance = OpenVpnManager(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            mService = IOpenVPNServiceInternal.Stub.asInterface(service)
            Log.d(TAG, "OpenVPN service connected")
            ensureLoadedStateOnResume()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mService = null
            Log.d(TAG, "OpenVPN service disconnected")
        }
    }

    init {
        VpnStatus.addStateListener(this)
        VpnStatus.addByteCountListener(this)
        restoreRuntimeState()
    }

    fun bindService(context: Context) {
        if (isBound) return
        try {
            val intent = Intent(context, OpenVPNService::class.java)
            intent.action = OpenVPNService.START_SERVICE
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            isBound = true
            ensureLoadedStateOnResume()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind service", e)
        }
    }

    fun unbindService(context: Context) {
        if (!isBound) return
        try {
            context.unbindService(serviceConnection)
            isBound = false
        } catch (e: Exception) {
            Log.e(TAG, "Error unbinding service", e)
        }
    }

    fun loadConfigFromInputStream(inputStream: InputStream, serverName: String): Boolean {
        return try {
            val reader = BufferedReader(InputStreamReader(inputStream))
            val originalConfig = reader.readText()
            reader.close()
            rawConfigContent = originalConfig
            currentConfigContent = applyProtocolOverride(originalConfig)

            val profile = parseConfig(currentConfigContent!!, serverName)
            Log.d(TAG, "Config loaded: $serverName, length: ${currentConfigContent?.length}, profile: ${profile != null}")
            profile != null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load config", e)
            false
        }
    }

    fun reloadCurrentConfigWithProtocol(serverName: String): Boolean {
        val raw = rawConfigContent ?: return false
        return try {
            currentConfigContent = applyProtocolOverride(raw)
            parseConfig(currentConfigContent!!, serverName) != null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reload config with protocol override", e)
            false
        }
    }

    private fun applyProtocolOverride(configContent: String): String {
        return when (VpnSettingsPreferences.getProtocolMode(context)) {
            VpnSettingsPreferences.PROTOCOL_UDP -> upsertProto(configContent, "udp")
            VpnSettingsPreferences.PROTOCOL_TCP -> upsertProto(configContent, "tcp-client")
            else -> configContent
        }
    }

    private fun upsertProto(configContent: String, protocol: String): String {
        val protoRegex = Regex("(?m)^\\s*proto\\s+\\S+\\s*$")
        return if (protoRegex.containsMatchIn(configContent)) {
            configContent.replace(protoRegex, "proto $protocol")
        } else {
            "proto $protocol\n$configContent"
        }
    }

    private fun parseConfig(configContent: String, name: String): VpnProfile? {
        return try {
            val cp = ConfigParser()
            cp.parseConfig(StringReader(configContent))
            val vp = cp.convertProfile()
            vp.mName = name
            vp.mAllowedAppsVpnAreDisallowed = true

            val pm = ProfileManager.getInstance(context)
            pm.addProfile(vp)
            pm.saveProfileList(context)
            pm.saveProfile(context, vp)

            vpnProfile = vp
            vp
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse config", e)
            null
        }
    }

    override fun connect(activity: Activity) {
        val vp = vpnProfile
        if (vp == null) {
            _statusMessage.value = "Конфигурация не загружена"
            _connectionState.value = VpnConnectionState.ERROR
            return
        }

        val vpnIntent = VpnService.prepare(activity)
        if (vpnIntent != null) {
            activity.startActivityForResult(vpnIntent, VPN_PERMISSION_REQUEST_CODE)
            return
        }

        startVpn(activity)
    }

    fun startVpn(context: Context) {
        val vp = vpnProfile
        if (vp == null) {
            _connectionState.value = VpnConnectionState.ERROR
            _statusMessage.value = "Конфигурация не загружена"
            return
        }

        try {
            _connectionState.value = VpnConnectionState.CONNECTING
            _statusMessage.value = "Подключение..."

            VPNLaunchHelper.startOpenVpn(vp, context)
            Log.d(TAG, "Manager started directly for profile: ${vp.mName}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start manager", e)
            _connectionState.value = VpnConnectionState.ERROR
            _statusMessage.value = "Ошибка подключения: ${e.message}"
        }
    }

    override fun disconnect() {
        try {
            _isPaused.value = false
            _connectionState.value = VpnConnectionState.DISCONNECTING
            _statusMessage.value = "Отключение..."
            persistRuntimeState()

            ProfileManager.setConntectedVpnProfileDisconnected(context)

            if (mService != null) {
                try {
                    mService?.stopVPN(false)
                    forceDisconnectJob?.cancel()
                    forceDisconnectJob = managerScope.launch {
                        delay(6000L)
                        if (_connectionState.value == VpnConnectionState.DISCONNECTING) {
                            _connectionState.value = VpnConnectionState.DISCONNECTED
                            _statusMessage.value = "Отключено"
                            persistRuntimeState()
                        }
                    }
                } catch (e: RemoteException) {
                    Log.e(TAG, "Ошибка остановки", e)
                }
            } else {
                _connectionState.value = VpnConnectionState.DISCONNECTED
                _statusMessage.value = "Отключено"
                persistRuntimeState()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disconnect", e)
            _connectionState.value = VpnConnectionState.DISCONNECTED
            _statusMessage.value = "Отключено"
            persistRuntimeState()
        }
    }

    override fun isConnected(): Boolean = _connectionState.value == VpnConnectionState.CONNECTED

    fun togglePause() {
        if (_isPaused.value) {
            resume()
        } else {
            pause()
        }
    }

    fun pause() {
        val service = mService ?: run {
            _statusMessage.value = "Сервис недоступен"
            return
        }
        try {
            service.userPause(true)
            _isPaused.value = true
            _statusMessage.value = "Пауза"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pause", e)
            _statusMessage.value = "Не удалось поставить на паузу"
        }
    }

    fun resume() {
        val service = mService ?: run {
            _statusMessage.value = "Сервис недоступен"
            return
        }
        try {
            service.userPause(false)
            _isPaused.value = false
            _statusMessage.value = "Возобновлен"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume", e)
            _statusMessage.value = "Не удалось возобновить"
        }
    }

    fun hasConfig(): Boolean = vpnProfile != null

    fun getServerInfo(): Pair<String, String>? {
        val config = currentConfigContent ?: return null
        var host = "unknown"
        var port = "1194"

        config.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("remote ")) {
                val parts = trimmed.split("\\s+".toRegex())
                if (parts.size >= 2) host = parts[1]
                if (parts.size >= 3) port = parts[2]
            }
        }
        return Pair(host, port)
    }

    fun getCurrentProtocolLabel(): String {
        val config = currentConfigContent ?: return "AUTO"
        config.lines().forEach { line ->
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

    override fun updateState(
        state: String?,
        logmessage: String?,
        localizedResId: Int,
        level: ConnectionStatus?,
        intent: Intent?
    ) {
        Log.d(TAG, "State: $state, level: $level, msg: $logmessage")

        _statusMessage.value = logmessage ?: state ?: ""
        val upperState = state?.uppercase()
        _isPaused.value = upperState == "USERPAUSE" || upperState == "SCREENOFF"

        when (level) {
            ConnectionStatus.LEVEL_CONNECTED -> {
                forceDisconnectJob?.cancel()
                _connectionState.value = VpnConnectionState.CONNECTED
                _statusMessage.value = "Подключено"
                persistRuntimeState()
            }
            ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED,
            ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
            ConnectionStatus.LEVEL_START,
            ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT -> {
                _connectionState.value = VpnConnectionState.CONNECTING
                persistRuntimeState()
            }
            ConnectionStatus.LEVEL_NOTCONNECTED -> {
                forceDisconnectJob?.cancel()
                _connectionState.value = VpnConnectionState.DISCONNECTED
                _statusMessage.value = "Отключено"
                _isPaused.value = false
                persistRuntimeState()
            }
            ConnectionStatus.LEVEL_AUTH_FAILED -> {
                _connectionState.value = VpnConnectionState.ERROR
                _statusMessage.value = "Ошибка авторизации"
                persistRuntimeState()
            }
            ConnectionStatus.LEVEL_NONETWORK -> {
                _connectionState.value = VpnConnectionState.ERROR
                _statusMessage.value = "Нет сети"
                persistRuntimeState()
            }
            else -> {}
        }
    }

    override fun setConnectedVPN(uuid: String?) {}

    override fun updateByteCount(inBytes: Long, outBytes: Long, diffIn: Long, diffOut: Long) {
        _byteCountIn.value = inBytes
        _byteCountOut.value = outBytes
    }

    fun cleanup() {
        VpnStatus.removeStateListener(this)
        VpnStatus.removeByteCountListener(this)
    }

    private fun normalizeDisconnectingStateIfNeeded() {
        if (_connectionState.value == VpnConnectionState.DISCONNECTING && mService == null) {
            _connectionState.value = VpnConnectionState.DISCONNECTED
            _statusMessage.value = "Отключено"
            persistRuntimeState()
        }
    }

    fun ensureLoadedStateOnResume() {
        normalizeDisconnectingStateIfNeeded()
        if (_connectionState.value == VpnConnectionState.DISCONNECTED && mService != null) {
            val lastState = runtimePrefs.getString(KEY_LAST_STATE, null)
            if (lastState == VpnConnectionState.CONNECTED.name) {
                _connectionState.value = VpnConnectionState.CONNECTED
                _statusMessage.value = "Подключено"
            }
        }
    }

    private fun restoreRuntimeState() {
        val lastStateName = runtimePrefs.getString(KEY_LAST_STATE, null) ?: return
        val lastStatus = runtimePrefs.getString(KEY_LAST_STATUS, "") ?: ""
        val lastTs = runtimePrefs.getLong(KEY_LAST_TS, 0L)
        val restoredState = runCatching { VpnConnectionState.valueOf(lastStateName) }
            .getOrDefault(VpnConnectionState.DISCONNECTED)
        val normalizedState = if (
            restoredState == VpnConnectionState.DISCONNECTING &&
            System.currentTimeMillis() - lastTs > 10_000L
        ) {
            VpnConnectionState.DISCONNECTED
        } else {
            restoredState
        }
        _connectionState.value = normalizedState
        _statusMessage.value = if (normalizedState == VpnConnectionState.DISCONNECTED) "Отключено" else lastStatus
    }

    private fun persistRuntimeState() {
        runtimePrefs.edit()
            .putString(KEY_LAST_STATE, _connectionState.value.name)
            .putString(KEY_LAST_STATUS, _statusMessage.value)
            .putLong(KEY_LAST_TS, System.currentTimeMillis())
            .apply()
    }

    fun resetUiStateToDisconnected() {
        forceDisconnectJob?.cancel()
        _connectionState.value = VpnConnectionState.DISCONNECTED
        _statusMessage.value = "Отключено"
        _isPaused.value = false
        persistRuntimeState()
    }
}
