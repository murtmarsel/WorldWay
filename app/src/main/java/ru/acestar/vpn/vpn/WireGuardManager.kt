package ru.acestar.vpn.vpn

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.VpnService
import android.text.format.Formatter
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.acestar.vpn.R
import java.io.BufferedReader
import java.io.StringReader

class WireGuardManager(private val context: Context) : VpnManagerInterface {

    private val TAG = "WireGuardManager"

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateMonitorJob: Job? = null

    private val backend: GoBackend by lazy { GoBackend(context) }
    private var tunnel: Tunnel? = null
    private var currentConfig: Config? = null
    private var currentConfigName: String = "WireGuard"

    private val runtimePrefs =
        context.getSharedPreferences("wg_runtime_state", Context.MODE_PRIVATE)

    // ─── STATE (реализует VpnManagerInterface) ────────────────────────────────

    private val _connectionState = MutableStateFlow(VpnConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<VpnConnectionState> = _connectionState.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    override val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _byteCountIn = MutableStateFlow(0L)
    override val byteCountIn: StateFlow<Long> = _byteCountIn.asStateFlow()

    private val _byteCountOut = MutableStateFlow(0L)
    override val byteCountOut: StateFlow<Long> = _byteCountOut.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    override val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    companion object {
        private const val KEY_LAST_STATE  = "last_state"
        private const val KEY_LAST_STATUS = "last_status"
        private const val KEY_LAST_NAME   = "last_name"

        const val VPN_PERMISSION_REQUEST_CODE = 1002

        private const val NOTIF_CHANNEL_ID = "wg_connection"
        private const val NOTIF_ID = 2001

        @Volatile
        private var INSTANCE: WireGuardManager? = null

        fun getInstance(context: Context): WireGuardManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: WireGuardManager(context.applicationContext).also { INSTANCE = it }
            }
    }

    init {
        createNotificationChannel()
    }

    // ─── TUNNEL ───────────────────────────────────────────────────────────────

    private inner class WgTunnel(private val name: String) : Tunnel {
        override fun getName(): String = name

        override fun onStateChange(newState: Tunnel.State) {
            Log.d(TAG, "Tunnel state → $newState")
            when (newState) {
                Tunnel.State.UP -> {
                    _connectionState.value = VpnConnectionState.CONNECTED
                    _statusMessage.value   = "Подключено"
                    persistRuntimeState()
                    showConnectionNotification(0L, 0L)
                }
                Tunnel.State.DOWN -> {
                    _connectionState.value = VpnConnectionState.DISCONNECTED
                    _statusMessage.value   = "Отключено"
                    stopMonitoring()
                    persistRuntimeState()
                    cancelConnectionNotification()
                }
                else -> {}
            }
        }
    }

    // ─── CONFIG ───────────────────────────────────────────────────────────────

    fun loadConfig(configContent: String, name: String): Boolean {
        return try {
            val patched = ensureDns(configContent)
            val config = Config.parse(BufferedReader(StringReader(patched)))
            currentConfig = config
            currentConfigName = name
            tunnel        = WgTunnel(name)
            Log.d(TAG, "WireGuard config loaded: $name, DNS servers: ${config.`interface`.dnsServers}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Config parse error", e)
            false
        }
    }

    /**
     * Если в конфиге нет строки DNS или она пустая — добавляем надёжные
     * публичные DNS-серверы. Это устраняет dns_probe_finished_bad_config
     * когда сервер не прописал DNS в конфиге.
     *
     * Если DNS уже есть — не трогаем (уважаем настройки сервера).
     */
    private fun ensureDns(configContent: String): String {
        val hasDns = configContent.lines().any { line ->
            val trimmed = line.trim()
            trimmed.startsWith("DNS", ignoreCase = true) &&
            trimmed.contains("=") &&
            trimmed.substringAfter("=").trim().isNotEmpty()
        }
        if (hasDns) return configContent

        // DNS отсутствует — вставляем после строки [Interface]
        val fallbackDns = "DNS = 1.1.1.1, 8.8.8.8"
        Log.d(TAG, "WireGuard config has no DNS — injecting fallback: $fallbackDns")
        return configContent.replace(
            Regex("(?i)^\\[Interface\\]", RegexOption.MULTILINE),
            "[Interface]\n$fallbackDns"
        )
    }

    fun hasConfig(): Boolean = currentConfig != null && tunnel != null

    // ─── CONNECT ──────────────────────────────────────────────────────────────

    /**
     * Запрашивает разрешение VPN если нужно, затем поднимает туннель.
     * FIX: requestCode = 1002, ловится в onActivityResult MainActivity.
     */
    override fun connect(activity: Activity) {
        if (!hasConfig()) {
            _connectionState.value = VpnConnectionState.ERROR
            _statusMessage.value   = "Конфигурация не загружена"
            return
        }
        val intent = VpnService.prepare(activity)
        if (intent != null) {
            activity.startActivityForResult(intent, VPN_PERMISSION_REQUEST_CODE)
            return
        }
        startTunnel()
    }

    /**
     * FIX: GoBackend.setState() — блокирующий нативный вызов.
     * Обязательно на Dispatchers.IO, иначе NetworkOnMainThreadException / ANR.
     */
    fun startTunnel() {
        val t   = tunnel        ?: return
        val cfg = currentConfig ?: return

        managerScope.launch {
            try {
                // Сброс счётчиков трафика — новая сессия начинается с нуля.
                // Без этого ViewModel возьмёт значения прошлой сессии как базу
                // и учёт трафика сломается (дельта уйдёт в минус).
                _byteCountIn.value  = 0L
                _byteCountOut.value = 0L

                _connectionState.value = VpnConnectionState.CONNECTING
                _statusMessage.value   = "Подключение..."

                withContext(Dispatchers.IO) {
                    backend.setState(t, Tunnel.State.UP, cfg)
                }

                // GoBackend.setState не всегда вызывает onStateChange — проверяем явно
                val realState = withContext(Dispatchers.IO) { backend.getState(t) }
                if (realState == Tunnel.State.UP) {
                    _connectionState.value = VpnConnectionState.CONNECTED
                    _statusMessage.value   = "Подключено"
                    persistRuntimeState()
                    showConnectionNotification(0L, 0L)
                }

                startStateMonitoring()

            } catch (e: Exception) {
                Log.e(TAG, "startTunnel failed", e)
                _connectionState.value = VpnConnectionState.ERROR
                _statusMessage.value   = "Ошибка подключения: ${e.message}"
                persistRuntimeState()
                cancelConnectionNotification()
            }
        }
    }

    // ─── DISCONNECT ───────────────────────────────────────────────────────────

    /**
     * FIX: backend.setState(DOWN) тоже блокирующий — на Dispatchers.IO.
     */
    override fun disconnect() {
        val t = tunnel ?: return

        managerScope.launch {
            try {
                _connectionState.value = VpnConnectionState.DISCONNECTING
                _statusMessage.value   = "Отключение..."
                persistRuntimeState()

                withContext(Dispatchers.IO) {
                    backend.setState(t, Tunnel.State.DOWN, null)
                }

                stopMonitoring()

                delay(1500)
                if (_connectionState.value == VpnConnectionState.DISCONNECTING) {
                    _connectionState.value = VpnConnectionState.DISCONNECTED
                    _statusMessage.value   = "Отключено"
                    persistRuntimeState()
                }
                cancelConnectionNotification()

            } catch (e: Exception) {
                Log.e(TAG, "Disconnect error", e)
                _connectionState.value = VpnConnectionState.DISCONNECTED
                _statusMessage.value   = "Отключено"
                persistRuntimeState()
            }
        }
    }

    override fun isConnected(): Boolean =
        _connectionState.value == VpnConnectionState.CONNECTED

    // ─── MONITORING ───────────────────────────────────────────────────────────

    private fun startStateMonitoring() {
        stateMonitorJob?.cancel()
        stateMonitorJob = managerScope.launch {
            while (isActive) {
                try {
                    val state = withContext(Dispatchers.IO) {
                        tunnel?.let { backend.getState(it) }
                    }

                    when (state) {
                        Tunnel.State.UP -> {
                            if (_connectionState.value != VpnConnectionState.CONNECTED) {
                                _connectionState.value = VpnConnectionState.CONNECTED
                                _statusMessage.value   = "Подключено"
                                persistRuntimeState()
                            }
                        }
                        Tunnel.State.DOWN -> {
                            if (_connectionState.value == VpnConnectionState.CONNECTED ||
                                _connectionState.value == VpnConnectionState.CONNECTING
                            ) {
                                _connectionState.value = VpnConnectionState.DISCONNECTED
                                _statusMessage.value   = "Отключено"
                                persistRuntimeState()
                            }
                        }
                        else -> {}
                    }

                    // ─── Статистика трафика ───────────────────────────────────
                    val t = tunnel
                    if (t != null && state == Tunnel.State.UP) {
                        try {
                            val stats = withContext(Dispatchers.IO) {
                                backend.getStatistics(t)
                            }
                            val rx = stats.totalRx()
                            val tx = stats.totalTx()
                            _byteCountIn.value  = rx
                            _byteCountOut.value = tx
                            // Обновляем уведомление с актуальным трафиком
                            showConnectionNotification(rx, tx)
                        } catch (e: Exception) {
                            // getStatistics может бросить если туннель только поднялся
                            Log.w(TAG, "getStatistics failed: ${e.message}")
                        }
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Monitor error", e)
                }

                delay(1000)
            }
        }
    }

    private fun stopMonitoring() {
        stateMonitorJob?.cancel()
        stateMonitorJob = null
    }

    // ─── UTILS ────────────────────────────────────────────────────────────────

    fun resetUiStateToDisconnected() {
        stopMonitoring()
        _connectionState.value = VpnConnectionState.DISCONNECTED
        _statusMessage.value   = "Отключено"
        _isPaused.value        = false
        persistRuntimeState()
    }

    /**
     * Вызывается из onResume. Проверяет реальное состояние туннеля через GoBackend
     * и обновляет _connectionState. Если туннель UP — запускает мониторинг.
     */
    fun ensureLoadedStateOnResume() {
        val t = tunnel ?: run {
            // Туннель не загружен — пробуем восстановить из SharedPrefs
            val lastState = runtimePrefs.getString(KEY_LAST_STATE, null)
            if (lastState == VpnConnectionState.CONNECTED.name) {
                // Туннель был подключён, но объект потерян (процесс пересоздан).
                // Реальное состояние неизвестно — сбрасываем в DISCONNECTED.
                _connectionState.value = VpnConnectionState.DISCONNECTED
                _statusMessage.value   = "Отключено"
                persistRuntimeState()
            }
            return
        }
        managerScope.launch {
            try {
                val state = withContext(Dispatchers.IO) { backend.getState(t) }
                when (state) {
                    Tunnel.State.UP -> {
                        _connectionState.value = VpnConnectionState.CONNECTED
                        _statusMessage.value   = "Подключено"
                        persistRuntimeState()
                        // Перезапускаем мониторинг если он не работает
                        if (stateMonitorJob?.isActive != true) startStateMonitoring()
                    }
                    Tunnel.State.DOWN -> {
                        if (_connectionState.value != VpnConnectionState.DISCONNECTED) {
                            _connectionState.value = VpnConnectionState.DISCONNECTED
                            _statusMessage.value   = "Отключено"
                            persistRuntimeState()
                        }
                    }
                    else -> {}
                }
            } catch (e: Exception) {
                Log.w(TAG, "ensureLoadedStateOnResume: getState failed: ${e.message}")
            }
        }
    }

    private fun persistRuntimeState() {
        runtimePrefs.edit()
            .putString(KEY_LAST_STATE,  _connectionState.value.name)
            .putString(KEY_LAST_STATUS, _statusMessage.value)
            .putString(KEY_LAST_NAME,   currentConfigName)
            .apply()
    }

    // ─── УВЕДОМЛЕНИЯ ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "WireGuard подключение",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Статус активного WireGuard подключения"
                setShowBadge(false)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Показывает/обновляет уведомление о текущем WireGuard-подключении.
     * Имя конфига [currentConfigName] + текущий трафик (как у OpenVPN).
     */
    private fun showConnectionNotification(rxBytes: Long, txBytes: Long) {
        try {
            val rx = Formatter.formatShortFileSize(context, rxBytes)
            val tx = Formatter.formatShortFileSize(context, txBytes)
            val text = "$currentConfigName · ↓$rx / ↑$tx"

            val notification = NotificationCompat.Builder(context, NOTIF_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_vpn_lock)
                .setContentTitle("WireGuard подключён")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIF_ID, notification)
        } catch (e: SecurityException) {
            // нет разрешения POST_NOTIFICATIONS — игнорируем
        }
    }

    private fun cancelConnectionNotification() {
        try {
            NotificationManagerCompat.from(context).cancel(NOTIF_ID)
        } catch (e: Exception) { /* no-op */ }
    }
}