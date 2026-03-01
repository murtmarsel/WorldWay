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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.StringReader

class OpenVpnManager(private val context: Context) : VpnStatus.StateListener, VpnStatus.ByteCountListener {

    private val _connectionState = MutableStateFlow(VpnConnectionState.DISCONNECTED)
    val connectionState: StateFlow<VpnConnectionState> = _connectionState.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _byteCountIn = MutableStateFlow(0L)
    val byteCountIn: StateFlow<Long> = _byteCountIn.asStateFlow()

    private val _byteCountOut = MutableStateFlow(0L)
    val byteCountOut: StateFlow<Long> = _byteCountOut.asStateFlow()

    private var currentConfigContent: String? = null
    private var vpnProfile: VpnProfile? = null
    private var mService: IOpenVPNServiceInternal? = null
    private var isBound = false

    companion object {
        private const val TAG = "OpenVpnManager"
        const val VPN_PERMISSION_REQUEST_CODE = 1001

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
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mService = null
            Log.d(TAG, "OpenVPN service disconnected")
        }
    }

    init {
        VpnStatus.addStateListener(this)
        VpnStatus.addByteCountListener(this)
    }

    fun bindService(context: Context) {
        if (isBound) return
        try {
            val intent = Intent(context, OpenVPNService::class.java)
            intent.action = OpenVPNService.START_SERVICE
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            isBound = true
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
            currentConfigContent = reader.readText()
            reader.close()

            val profile = parseConfig(currentConfigContent!!, serverName)
            Log.d(TAG, "Config loaded: $serverName, length: ${currentConfigContent?.length}, profile: ${profile != null}")
            profile != null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load config", e)
            false
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

    fun connect(activity: Activity) {
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
            Log.d(TAG, "VPN started directly for profile: ${vp.mName}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN", e)
            _connectionState.value = VpnConnectionState.ERROR
            _statusMessage.value = "Ошибка подключения: ${e.message}"
        }
    }

    fun disconnect() {
        try {
            _connectionState.value = VpnConnectionState.DISCONNECTING
            _statusMessage.value = "Отключение..."

            ProfileManager.setConntectedVpnProfileDisconnected(context)

            if (mService != null) {
                try {
                    mService?.stopVPN(false)
                } catch (e: RemoteException) {
                    Log.e(TAG, "Error stopping VPN via service", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disconnect", e)
            _connectionState.value = VpnConnectionState.DISCONNECTED
        }
    }

    fun isConnected(): Boolean = _connectionState.value == VpnConnectionState.CONNECTED

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

    override fun updateState(
        state: String?,
        logmessage: String?,
        localizedResId: Int,
        level: ConnectionStatus?,
        intent: Intent?
    ) {
        Log.d(TAG, "State: $state, level: $level, msg: $logmessage")

        _statusMessage.value = logmessage ?: state ?: ""

        when (level) {
            ConnectionStatus.LEVEL_CONNECTED -> {
                _connectionState.value = VpnConnectionState.CONNECTED
                _statusMessage.value = "Подключено"
            }
            ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED,
            ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
            ConnectionStatus.LEVEL_START,
            ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT -> {
                _connectionState.value = VpnConnectionState.CONNECTING
            }
            ConnectionStatus.LEVEL_NOTCONNECTED -> {
                _connectionState.value = VpnConnectionState.DISCONNECTED
                _statusMessage.value = "Отключено"
            }
            ConnectionStatus.LEVEL_AUTH_FAILED -> {
                _connectionState.value = VpnConnectionState.ERROR
                _statusMessage.value = "Ошибка авторизации"
            }
            ConnectionStatus.LEVEL_NONETWORK -> {
                _connectionState.value = VpnConnectionState.ERROR
                _statusMessage.value = "Нет сети"
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
}
