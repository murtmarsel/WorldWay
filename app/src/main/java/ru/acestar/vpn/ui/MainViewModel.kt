package ru.acestar.vpn.ui

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import ru.acestar.vpn.VpnApplication
import ru.acestar.vpn.data.model.User
import ru.acestar.vpn.data.model.VpnConfigEntity
import ru.acestar.vpn.data.model.VpnServerConfigEntity
import ru.acestar.vpn.data.repository.SessionManager
import ru.acestar.vpn.vpn.OpenVpnManager
import ru.acestar.vpn.vpn.VpnConnectionState
import ru.acestar.vpn.vpn.VpnManagerInterface
import ru.acestar.vpn.vpn.WireGuardManager
import java.io.ByteArrayInputStream

enum class ActiveVpnProtocol { NONE, OPENVPN, WIREGUARD }

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        const val SUBSCRIPTION_WARN_MS = 1L * 24L * 60L * 60L * 1000L
    }

    // ─── Менеджеры ───────────────────────────────────────────────────────────
    val vpnManager:       OpenVpnManager  = OpenVpnManager.getInstance(application)
    val wireGuardManager: WireGuardManager = WireGuardManager.getInstance(application)

    // ─── Активный протокол ───────────────────────────────────────────────────
    private val _activeProtocol = MutableStateFlow(ActiveVpnProtocol.NONE)
    val activeProtocol: StateFlow<ActiveVpnProtocol> = _activeProtocol.asStateFlow()

    // ─── Проксированные потоки UI ────────────────────────────────────────────
    private val _connectionState = MutableStateFlow(VpnConnectionState.DISCONNECTED)
    val connectionState: StateFlow<VpnConnectionState> = _connectionState.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _byteCountIn = MutableStateFlow(0L)
    val byteCountIn: StateFlow<Long> = _byteCountIn.asStateFlow()

    private val _byteCountOut = MutableStateFlow(0L)
    val byteCountOut: StateFlow<Long> = _byteCountOut.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private var protocolObserverJob: Job? = null

    // ─── БД / сессия ─────────────────────────────────────────────────────────
    private val database           = (application as VpnApplication).database
    private val vpnConfigDao       = database.vpnConfigDao()
    private val vpnServerConfigDao = database.vpnServerConfigDao()
    private val sessionManager     = SessionManager.getInstance(application)

    private var currentUserId = -1L

    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()

    private val _configLoaded = MutableStateFlow(false)
    val configLoaded: StateFlow<Boolean> = _configLoaded.asStateFlow()

    private val _vpnConfigs = MutableStateFlow<List<VpnConfigEntity>>(emptyList())
    val vpnConfigs: StateFlow<List<VpnConfigEntity>> = _vpnConfigs.asStateFlow()

    // ─── Авто-режим ──────────────────────────────────────────────────────────
    private val _autoModeState = MutableStateFlow<AutoModeState>(AutoModeState.Idle)
    val autoModeState: StateFlow<AutoModeState> = _autoModeState.asStateFlow()

    private var autoQueue: List<VpnServerConfigEntity> = emptyList()
    private var autoIndex  = 0
    private var autoActive = false
    private var autoJob: Job? = null

    sealed class AutoModeState {
        object Idle : AutoModeState()
        data class Trying(
            val index: Int,
            val total: Int,
            val name:  String,
            val type:  String
        ) : AutoModeState()
        data class Connected(val config: VpnServerConfigEntity) : AutoModeState()
        object AllFailed : AutoModeState()
    }

    // ─── Init ─────────────────────────────────────────────────────────────────
    init {
        loadCurrentUser()
        switchProtocolObserver(vpnManager) // слушаем OpenVPN по умолчанию
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ПЕРЕКЛЮЧЕНИЕ ПРОТОКОЛА
    // Принимает VpnManagerInterface — никаких Unresolved reference.
    // ═══════════════════════════════════════════════════════════════════════

    fun setActiveProtocol(protocol: ActiveVpnProtocol) {
        if (_activeProtocol.value == protocol) return
        _activeProtocol.value = protocol

        val manager: VpnManagerInterface = when (protocol) {
            ActiveVpnProtocol.WIREGUARD -> wireGuardManager
            else                        -> vpnManager
        }
        switchProtocolObserver(manager)
    }

    /**
     * Перенаправляет все 5 state-потоков из [manager] в поля ViewModel.
     * Явный тип VpnManagerInterface — компилятор точно знает все поля.
     */
    private fun switchProtocolObserver(manager: VpnManagerInterface) {
        protocolObserverJob?.cancel()
        protocolObserverJob = viewModelScope.launch {
            val jobs = listOf(
                launch { manager.connectionState.collectLatest { _connectionState.value = it } },
                launch { manager.statusMessage.collectLatest   { _statusMessage.value  = it } },
                launch { manager.byteCountIn.collectLatest     { _byteCountIn.value    = it } },
                launch { manager.byteCountOut.collectLatest    { _byteCountOut.value   = it } },
                launch { manager.isPaused.collectLatest        { _isPaused.value       = it } }
            )
            try {
                awaitCancellation()
            } finally {
                jobs.forEach { it.cancel() }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // АВТО-РЕЖИМ
    // Перебирает vpn_server_configs. Поле type определяет менеджер.
    // ═══════════════════════════════════════════════════════════════════════

    fun startAutoMode(activity: Activity) {
        if (autoActive) return
        stopAutoMode()

        autoJob = viewModelScope.launch {
            autoActive = true
            autoIndex  = 0
            _autoModeState.value = AutoModeState.Idle

            val configs = vpnServerConfigDao.getAllByOrder(currentUserId)

            if (configs.isEmpty()) {
                _autoModeState.value = AutoModeState.AllFailed
                autoActive = false
                return@launch
            }

            autoQueue = configs

            // Линейный перебор без рекурсии — одна корутина, один цикл
            while (autoIndex < autoQueue.size && autoActive) {
                val config = autoQueue[autoIndex]

                _autoModeState.value = AutoModeState.Trying(
                    index = autoIndex + 1,
                    total = autoQueue.size,
                    name  = config.name,
                    type  = config.type
                )

                // 1. Отключаем предыдущее и ждём полного отключения
                val prevManager = activeManager()
                if (_connectionState.value != VpnConnectionState.DISCONNECTED) {
                    prevManager.disconnect()
                    waitForDisconnect(timeoutMs = 8_000)
                }

                // 2. Переключаем протокол и грузим конфиг
                val loaded = when (config.type.lowercase()) {
                    "wireguard" -> {
                        setActiveProtocol(ActiveVpnProtocol.WIREGUARD)
                        wireGuardManager.loadConfig(config.content, config.name)
                    }
                    "openvpn" -> {
                        setActiveProtocol(ActiveVpnProtocol.OPENVPN)
                        vpnManager.loadConfigFromInputStream(
                            ByteArrayInputStream(config.content.toByteArray()),
                            config.name
                        )
                    }
                    else -> false
                }

                if (!loaded) {
                    // Конфиг не распарсился — сразу к следующему
                    autoIndex++
                    continue
                }

                // 3. Небольшая пауза после смены протокола
                delay(400)

                // 4. Подключаем
                activeManager().connect(activity)

                // 5. Ждём результата (таймаут 20 сек)
                val success = waitForConnectionResult(timeoutMs = 20_000)

                if (success) {
                    _autoModeState.value = AutoModeState.Connected(config)
                    onConfigLoaded()
                    autoActive = false
                    return@launch
                }

                // Подключение не удалось — переходим к следующему
                autoIndex++
            }

            // Все конфиги перебраны без успеха
            if (autoActive) {
                _autoModeState.value = AutoModeState.AllFailed
                autoActive = false
            }
        }
    }

    /**
     * Ждёт перехода в DISCONNECTED или истечения таймаута.
     * Нужно чтобы не начинать следующую попытку пока предыдущий туннель ещё закрывается.
     */
    private suspend fun waitForDisconnect(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (_connectionState.value == VpnConnectionState.DISCONNECTED) return
            delay(300)
        }
        // Таймаут — принудительно считаем отключённым и продолжаем
    }

    /**
     * Ждёт CONNECTED, ERROR или DISCONNECTED (неожиданный обрыв).
     * DISCONNECTING и CONNECTING — промежуточные состояния, продолжаем ждать.
     */
    private suspend fun waitForConnectionResult(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            when (_connectionState.value) {
                VpnConnectionState.CONNECTED    -> return true
                VpnConnectionState.ERROR        -> return false
                // DISCONNECTED во время ожидания подключения = обрыв/отказ
                VpnConnectionState.DISCONNECTED -> {
                    // Даём небольшую паузу на случай кратковременного DISCONNECTED
                    // при смене состояния (race condition между disconnect и connect)
                    delay(800)
                    if (_connectionState.value == VpnConnectionState.DISCONNECTED) return false
                }
                else -> delay(500) // CONNECTING / DISCONNECTING — ждём
            }
        }
        return false // таймаут
    }

    fun stopAutoMode() {
        autoJob?.cancel()
        autoJob    = null
        autoActive = false
        autoIndex  = 0
        _autoModeState.value = AutoModeState.Idle
    }

    val isAutoModeActive: Boolean get() = autoActive

    /** Возвращает текущий активный менеджер как VpnManagerInterface. */
    private fun activeManager(): VpnManagerInterface =
        if (_activeProtocol.value == ActiveVpnProtocol.WIREGUARD) wireGuardManager else vpnManager

    // ═══════════════════════════════════════════════════════════════════════
    // РУЧНОЕ ПОДКЛЮЧЕНИЕ
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Загружает конфиг из vpn_configs (поле type = "OpenVPN" | "WireGuard").
     * Переключает activeProtocol автоматически.
     * Вызывается при выборе избранного или конфига из списка.
     */
    fun loadUserConfig(content: String, name: String, type: String): Boolean {
        return when (type.lowercase()) {
            "wireguard" -> {
                setActiveProtocol(ActiveVpnProtocol.WIREGUARD)
                wireGuardManager.loadConfig(content, name)
            }
            "openvpn" -> {
                setActiveProtocol(ActiveVpnProtocol.OPENVPN)
                vpnManager.loadConfigFromInputStream(
                    ByteArrayInputStream(content.toByteArray()),
                    name
                )
            }
            else -> false
        }
    }

    /**
     * Подключает активный менеджер.
     * Кнопка "Подключиться" в MainActivity.
     */
    fun connectActive(activity: Activity) {
        if (_activeProtocol.value == ActiveVpnProtocol.NONE) return
        activeManager().connect(activity)
    }

    /**
     * Отключает активный менеджер + сбрасывает авто-режим.
     * Кнопка "Отключить" в MainActivity.
     */
    fun disconnectActive() {
        stopAutoMode()
        activeManager().disconnect()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CONFIG LOADED
    // ═══════════════════════════════════════════════════════════════════════

    fun onConfigLoaded()    { _configLoaded.value = true  }
    fun clearConfigLoaded() { _configLoaded.value = false }

    /**
     * Вызывается при onResume: если VPN уже подключён (состояние восстановлено из менеджера),
     * помечаем configLoaded = true чтобы кнопка "Подключиться" была активна.
     */
    fun restoreConfigLoadedIfConnected() {
        if (_connectionState.value == VpnConnectionState.CONNECTED) {
            _configLoaded.value = true
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ПОЛЬЗОВАТЕЛЬ
    // ═══════════════════════════════════════════════════════════════════════

    private fun loadCurrentUser() {
        viewModelScope.launch {
            val user = sessionManager.getCurrentUser()
            currentUserId = user?.id ?: -1L
            observeVpnConfigs()
        }
        viewModelScope.launch {
            sessionManager.currentUser.collectLatest { _currentUser.value = it }
        }
    }

    fun refreshUser() = loadCurrentUser()

    fun hasActiveSubscription(): Boolean =
        _currentUser.value?.hasActiveSubscription() == true

    fun shouldShowEmailUnconfirmedBanner(): Boolean =
        _currentUser.value?.let { !it.emailConfirmed } == true

    fun shouldShowSubscriptionWarningBanner(): Boolean {
        val u = _currentUser.value ?: return false
        return !u.hasActiveSubscription() || u.subscriptionExpiresWithin(SUBSCRIPTION_WARN_MS)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // VPN CONFIGS (пользовательские — vpn_configs)
    // ═══════════════════════════════════════════════════════════════════════

    private fun observeVpnConfigs() {
        if (currentUserId == -1L) return
        viewModelScope.launch {
            vpnConfigDao.observeAllByUser(currentUserId).collectLatest {
                _vpnConfigs.value = it
            }
        }
    }

    suspend fun getConfigById(id: Long): VpnConfigEntity? {
        if (currentUserId == -1L) return null
        return vpnConfigDao.getByIdForUser(id, currentUserId)
    }

    suspend fun deleteManualConfig(id: Long) {
        if (currentUserId == -1L) return
        vpnConfigDao.deleteManualById(id, currentUserId)
    }

    suspend fun setFavoriteConfig(id: Long, favorite: Boolean) {
        if (currentUserId == -1L) return
        vpnConfigDao.setFavorite(id, currentUserId, favorite)
    }

    suspend fun getFavoriteCount(): Int {
        if (currentUserId == -1L) return 0
        return vpnConfigDao.getFavoriteCount(currentUserId)
    }
}