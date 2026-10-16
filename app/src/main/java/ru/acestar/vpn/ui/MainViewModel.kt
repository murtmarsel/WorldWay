package ru.acestar.vpn.ui

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
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
import ru.acestar.vpn.R
import ru.acestar.vpn.VpnApplication
import ru.acestar.vpn.data.model.Tariff
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
        private const val NOTIF_CHANNEL_ID = "vpn_tariff"
        private const val NOTIF_ID_TRAFFIC_80  = 1001
        private const val NOTIF_ID_TRAFFIC_100 = 1002
        private const val NOTIF_ID_EXPIRY      = 1003

        /** Интервал отправки трафика на сервер при активном лимитном подключении — 5 минут */
        private const val TRAFFIC_SERVER_PUSH_INTERVAL_MS = 5L * 60L * 1000L
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
    private val userDao            = database.userDao()
    private val sessionManager     = SessionManager.getInstance(application)
    private val authApiService     = ru.acestar.vpn.api.AuthApiService(sessionManager)
    private val userRepository     = ru.acestar.vpn.data.repository.UserRepository(userDao)

    private var currentUserId = -1L

    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()

    private val _configLoaded = MutableStateFlow(false)
    val configLoaded: StateFlow<Boolean> = _configLoaded.asStateFlow()

    private val _vpnConfigs = MutableStateFlow<List<VpnConfigEntity>>(emptyList())
    val vpnConfigs: StateFlow<List<VpnConfigEntity>> = _vpnConfigs.asStateFlow()

    // ─── Контроль трафика ────────────────────────────────────────────────────
    private val _trafficExhausted = MutableStateFlow(false)
    val trafficExhausted: StateFlow<Boolean> = _trafficExhausted.asStateFlow()

    /** true = кнопка Авто заблокирована из-за исчерпания трафика или тарифа */
    private val _autoBlocked = MutableStateFlow(false)
    val autoBlocked: StateFlow<Boolean> = _autoBlocked.asStateFlow()

    /** Причина блокировки кнопки Авто — показывается пользователю */
    private val _autoBlockReason = MutableStateFlow("")
    val autoBlockReason: StateFlow<String> = _autoBlockReason.asStateFlow()

    private var notified80Percent = false
    private var notified100Percent = false
    private var notifiedExpiry = false

    /**
     * Последнее «сырое» показание счётчика сессии (in+out), которое мы уже учли.
     * Дельта-учёт с детекцией сброса: если текущее показание меньше этого —
     * счётчик обнулился (началась новая сессия), значит весь текущий объём —
     * это прирост. Такой подход устойчив к гонкам обновления проксированных
     * потоков байт и к тому, сбрасывает ли менеджер счётчики.
     */
    private var lastRawSessionBytes = 0L
    private var trafficMonitorJob: Job? = null
    private var periodicSyncJob: Job? = null

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

    /** Результат проверки трафика перед запуском авто-режима */
    sealed class TrafficCheckResult {
        object Ok : TrafficCheckResult()
        data class Blocked(val reason: String) : TrafficCheckResult()
    }

    // ─── Init ─────────────────────────────────────────────────────────────────
    init {
        loadCurrentUser()
        if (wireGuardManager.isConnected()) setActiveProtocol(ActiveVpnProtocol.WIREGUARD)
        else setActiveProtocol(ActiveVpnProtocol.OPENVPN)
        viewModelScope.launch {
            wireGuardManager.connectionState.collectLatest {
                if (it == VpnConnectionState.CONNECTED) setActiveProtocol(ActiveVpnProtocol.WIREGUARD)
            }
        }
        viewModelScope.launch {
            vpnManager.connectionState.collectLatest {
                if (it == VpnConnectionState.CONNECTED) setActiveProtocol(ActiveVpnProtocol.OPENVPN)
            }
        }
        createNotificationChannel()
        observeConnectionForTraffic()
        startPeriodicSync()
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
                launch { manager.connectionState.collectLatest {
                    _connectionState.value = it
                    if (it == VpnConnectionState.CONNECTED) _configLoaded.value = true
                } },
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

    fun startAutoMode(activity: Activity, group: String = "auto1") {
        if (autoActive) return
        stopAutoMode()

        autoJob = viewModelScope.launch {
            autoActive = true
            autoIndex  = 0
            _autoModeState.value = AutoModeState.Idle

            // Убеждаемся что userId известен — без него конфиги не загрузятся
            if (currentUserId == -1L) {
                currentUserId = sessionManager.getCurrentUser()?.id ?: -1L
            }
            if (currentUserId == -1L) {
                _autoModeState.value = AutoModeState.AllFailed
                autoActive = false
                return@launch
            }

            // ПРОВЕРКА ДОСТУПА: трафик + срок тарифа (с синхронизацией сервера)
            val access = checkAccessBeforeAuto()
            if (access is TrafficCheckResult.Blocked) {
                _autoModeState.value = AutoModeState.AllFailed
                autoActive = false
                return@launch
            }

            // Если в БД нет конфигов — пробуем подгрузить с сервера
            if (vpnServerConfigDao.countByUserId(currentUserId) == 0) {
                ensureServerConfigsLoaded(force = true)
            }

            // Берём конфиги конкретной группы; если группа пустая — fallback на все конфиги
            val configs = if (vpnServerConfigDao.countByGroup(currentUserId, group) > 0) {
                vpnServerConfigDao.getAllByGroup(currentUserId, group)
            } else {
                vpnServerConfigDao.getAllByOrder(currentUserId)
            }

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

                // 2. Переключаем протокол и грузим конфиг.
                // Имя для уведомления: в авто-режиме "Авто O" / "Авто W"
                val autoLabel = if (config.type.lowercase().let {
                        it == "wireguard" || it == "amneziawg"
                    }) "Авто W" else "Авто O"

                val loaded = when (config.type.lowercase()) {
                    "wireguard", "amneziawg" -> {
                        setActiveProtocol(ActiveVpnProtocol.WIREGUARD)
                        wireGuardManager.loadConfig(config.content, autoLabel)
                    }
                    "openvpn" -> {
                        setActiveProtocol(ActiveVpnProtocol.OPENVPN)
                        vpnManager.loadConfigFromInputStream(
                            ByteArrayInputStream(config.content.toByteArray()),
                            autoLabel
                        )
                    }
                    else -> false
                }

                if (!loaded) {
                    autoIndex++
                    continue
                }

                // 3. Небольшая пауза после смены протокола
                delay(400)

                // 4. Подключаем
                activeManager().connect(activity)

                // 5. Ждём результата (таймаут 25 сек — даём больше времени на рукопожатие)
                val success = waitForConnectionResult(timeoutMs = 25_000)

                if (success) {
                    // Запас стабильности: убеждаемся что соединение держится 1.5 сек
                    // (фильтрует ложные CONNECTED которые сразу обрываются → ERR_CONNECTION_CLOSED)
                    delay(1_500)
                    if (_connectionState.value == VpnConnectionState.CONNECTED) {
                        _autoModeState.value = AutoModeState.Connected(config)
                        onConfigLoaded()
                        autoActive = false
                        return@launch
                    }
                    // Соединение оборвалось сразу после подключения — следующий
                }

                autoIndex++
            }

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
        // Проверяем доступ перед ручным подключением
        val user = _currentUser.value
        if (user != null && !enforceAccessRestrictions(user)) {
            return // доступ заблокирован — enforceAccessRestrictions уже выставил флаги
        }
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
    fun restoreVpnState(activity: Activity) {
        vpnManager.bindService(activity)
        vpnManager.ensureLoadedStateOnResume()
        wireGuardManager.ensureLoadedStateOnResume()
        when {
            wireGuardManager.isConnected() -> setActiveProtocol(ActiveVpnProtocol.WIREGUARD)
            vpnManager.isConnected() -> setActiveProtocol(ActiveVpnProtocol.OPENVPN)
        }
        restoreConfigLoadedIfConnected()
    }

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

    fun refreshUser() {
        viewModelScope.launch {
            // 1. Восстанавливаем userId из сессии
            val sessionUser = sessionManager.getCurrentUser()
            if (sessionUser != null && currentUserId != sessionUser.id) {
                currentUserId = sessionUser.id
                observeVpnConfigs()
            } else if (currentUserId == -1L) {
                currentUserId = sessionUser?.id ?: -1L
                if (currentUserId != -1L) observeVpnConfigs()
            }

            // 2. Сначала показываем локальные данные (быстро)
            if (currentUserId > 0) {
                val dbUser = userDao.getUserById(currentUserId)
                if (dbUser != null) {
                    _currentUser.value = dbUser
                    sessionManager.updateCurrentUser(dbUser)
                    refreshAutoBlockState(dbUser)
                }
            }

            // 3. Затем в фоне запрашиваем актуальные данные с сервера
            // (обновляет токен если нужно, получает свежий тариф/трафик)
            try {
                authApiService.refreshOnResume(userRepository)
                sessionManager.getCurrentUser()?.let { refreshedUser ->
                    if (currentUserId != refreshedUser.id) {
                        currentUserId = refreshedUser.id
                        observeVpnConfigs()
                    }
                }
                // После серверного обновления читаем свежие данные из БД
                if (currentUserId > 0) {
                    val updated = userDao.getUserById(currentUserId)
                    if (updated != null) {
                        _currentUser.value = updated
                        sessionManager.updateCurrentUser(updated)
                        refreshAutoBlockState(updated)
                        ensureServerConfigsLoaded()
                    }
                }
            } catch (e: Exception) {
                // Сеть недоступна — работаем с локальными данными
                android.util.Log.w("MainViewModel", "refreshUser server sync failed: ${e.message}")
                ensureServerConfigsLoaded()
            }

            if (_currentUser.value == null) {
                loadCurrentUser()
            }
        }
    }

    /**
     * Обновляет флаг блокировки Авто без отключения VPN.
     * Используется при обновлении данных пользователя.
     */
    private fun refreshAutoBlockState(user: User) {
        val app = getApplication<Application>()
        when {
            user.isTrafficExhausted() -> {
                _autoBlocked.value = true
                _autoBlockReason.value = app.getString(R.string.notif_traffic_exhausted_title)
            }
            !user.hasActiveSubscription() -> {
                _autoBlocked.value = true
                _autoBlockReason.value = app.getString(R.string.subscription_expired)
            }
            else -> {
                _autoBlocked.value = false
                _autoBlockReason.value = ""
            }
        }
    }

    /**
     * Проверяет наличие серверных конфигов в БД.
     * Если таблица пустая — загружает с сервера через AuthApiService.
     * Вызывается при refreshUser() чтобы авто-режим всегда имел конфиги.
     *
     * @param force если true — перезагружает даже если конфиги уже есть
     */
    private suspend fun ensureServerConfigsLoaded(force: Boolean = false) {
        if (currentUserId == -1L) return
        try {
            if (!force) {
                val count = vpnServerConfigDao.countByUserId(currentUserId)
                if (count > 0) return // конфиги уже есть — ничего не делаем
            }

            val user = sessionManager.getCurrentUser() ?: return
            val token = sessionManager.getAccessToken() ?: return

            val result = authApiService.getClientFilesList(user.email, token)
            result.onSuccess { files ->
                val entities = files
                    .filter { !it.content.isNullOrBlank() }
                    .map { file ->
                        VpnServerConfigEntity(
                            userId = currentUserId,
                            name = file.name,
                            type = file.type.lowercase(),
                            category = file.category?.lowercase() ?: "vpn",
                            protocol = file.protocol?.lowercase() ?: "",
                            content = file.content!!,
                            autoGroup = file.group?.lowercase()?.let {
                                if (it == "auto2") "auto2" else "auto1"
                            } ?: "auto1"
                        )
                    }
                if (entities.isNotEmpty()) {
                    // Атомарная замена — старые конфиги удаляются, новые вставляются
                    vpnServerConfigDao.replaceAllForUser(currentUserId, entities)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("MainViewModel", "ensureServerConfigsLoaded failed: ${e.message}")
        }
    }

    /**
     * Принудительная синхронизация серверных конфигов (например после логина).
     * Вызывается из MainActivity при первом запуске.
     */
    fun syncServerConfigsFromServer() {
        viewModelScope.launch {
            if (currentUserId == -1L) {
                currentUserId = sessionManager.getCurrentUser()?.id ?: -1L
            }
            ensureServerConfigsLoaded(force = true)
        }
    }

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

    private var vpnConfigsObserverJob: Job? = null
    private var observingForUserId: Long = -1L

    private fun observeVpnConfigs() {
        if (currentUserId == -1L) return
        // Не перезапускаем если уже наблюдаем за тем же пользователем
        if (observingForUserId == currentUserId && vpnConfigsObserverJob?.isActive == true) return
        vpnConfigsObserverJob?.cancel()
        observingForUserId = currentUserId
        vpnConfigsObserverJob = viewModelScope.launch {
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

    // ═══════════════════════════════════════════════════════════════════════
    // КОНТРОЛЬ ТРАФИКА
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Следит за состоянием подключения: при CONNECTED запускает мониторинг трафика,
     * при DISCONNECTED — сохраняет использованный трафик в БД.
     */
    private fun observeConnectionForTraffic() {
        viewModelScope.launch {
            _connectionState.collectLatest { state ->
                when (state) {
                    VpnConnectionState.CONNECTED -> {
                        // База учёта НЕ берётся из проксированного _byteCountIn —
                        // оно может запаздывать (гонка потоков) и содержать значение
                        // прошлой сессии. Дельту с детекцией сброса считает
                        // accountSessionTrafficDelta(), поэтому базу не трогаем.
                        startTrafficMonitor()
                    }
                    VpnConnectionState.DISCONNECTED,
                    VpnConnectionState.ERROR -> {
                        stopTrafficMonitor()
                        // Запускаем в отдельной корутине viewModelScope, чтобы
                        // collectLatest не отменил сохранение при быстром переподключении.
                        viewModelScope.launch { saveSessionTraffic() }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun startTrafficMonitor() {
        trafficMonitorJob?.cancel()
        trafficMonitorJob = viewModelScope.launch {
            var msSinceServerPush = 0L
            while (true) {
                delay(5_000) // проверяем каждые 5 секунд
                // Копим потреблённый в сессии трафик в локальную БД (дельта с прошлого учёта)
                accountSessionTrafficDelta()
                checkTrafficLimit()
                checkSubscriptionExpiry()

                // Раз в 5 минут при лимитном тарифе — отправляем актуальный трафик на сервер
                msSinceServerPush += 5_000
                if (msSinceServerPush >= TRAFFIC_SERVER_PUSH_INTERVAL_MS) {
                    msSinceServerPush = 0L
                    val u = userDao.getUserById(currentUserId)
                    if (u != null) pushTrafficToServerIfLimited(u)
                }
            }
        }
    }

    private fun stopTrafficMonitor() {
        trafficMonitorJob?.cancel()
        trafficMonitorJob = null
    }

    /**
     * Учитывает потреблённый в текущей сессии трафик в локальной БД.
     *
     * Считает прирост (дельту) относительно последнего учтённого показания с
     * ДЕТЕКЦИЕЙ СБРОСА: если текущее показание счётчика меньше предыдущего,
     * значит счётчик обнулился (новая сессия) — тогда прирост это всё текущее
     * значение. Такой алгоритм не задваивает трафик, не уходит в минус и не
     * зависит от гонок обновления проксированных потоков байт.
     *
     * Обновляет _currentUser и сессию.
     * @return актуальный пользователь из БД (или null если userId неизвестен)
     */
    private suspend fun accountSessionTrafficDelta(): User? {
        if (currentUserId == -1L) return null
        val currentSessionBytes = _byteCountIn.value + _byteCountOut.value
        val delta = if (currentSessionBytes >= lastRawSessionBytes) {
            currentSessionBytes - lastRawSessionBytes
        } else {
            // Счётчик сброшен → весь текущий объём является приростом
            currentSessionBytes
        }
        lastRawSessionBytes = currentSessionBytes
        if (delta > 0L) {
            userDao.addUsedTraffic(currentUserId, delta)
        }
        val updated = userDao.getUserById(currentUserId)
        if (updated != null) {
            _currentUser.value = updated
            sessionManager.updateCurrentUser(updated)
        }
        return updated
    }

    private suspend fun saveSessionTraffic() {
        if (currentUserId == -1L) return

        // Досчитываем оставшийся трафик сессии в локальную БД (параллельно с отправкой)
        val updated = accountSessionTrafficDelta() ?: return

        // Для лимитных тарифов — отправляем актуальный трафик на сервер
        pushTrafficToServerIfLimited(updated)

        // Перепроверяем доступ — мог исчерпаться трафик
        val fresh = userDao.getUserById(currentUserId) ?: updated
        enforceAccessRestrictions(fresh)
    }

    /**
     * Отправляет актуальное потребление трафика на сервер.
     * Только для тарифов с лимитом (безлимитным незачем).
     * Сервер и клиент сверяют значения — используется большее (источник правды).
     */
    private suspend fun pushTrafficToServerIfLimited(user: User) {
        val limit = user.effectiveTrafficLimit() ?: return // безлимит — не отправляем
        if (limit <= 0L) return
        try {
            // Берём самое свежее локальное значение из БД
            val localUsed = userDao.getUsedTraffic(currentUserId) ?: user.usedTrafficBytes
            authApiService.syncTrafficToServer(localUsed, userRepository)
            // syncTrafficToServer сохраняет в БД максимум из локального и серверного
            val refreshed = userDao.getUserById(currentUserId)
            if (refreshed != null) {
                _currentUser.value = refreshed
                sessionManager.updateCurrentUser(refreshed)
            }
        } catch (e: Exception) {
            android.util.Log.w("MainViewModel", "pushTraffic failed: ${e.message}")
        }
    }

    private fun checkTrafficLimit() {
        val user = _currentUser.value ?: return
        val limit = user.effectiveTrafficLimit() ?: return // безлимит — не проверяем
        val ratio = user.trafficUsageRatio() ?: return

        // 80% — предупреждение (один раз)
        if (ratio >= 0.8f && !notified80Percent) {
            notified80Percent = true
            val remaining = Tariff.formatUsed(user.remainingTrafficBytes() ?: 0L)
            sendNotification(
                id = NOTIF_ID_TRAFFIC_80,
                title = getApplication<Application>().getString(R.string.notif_traffic_80_title),
                text = getApplication<Application>().getString(
                    R.string.notif_traffic_80_text, remaining
                )
            )
        }

        // 100% — трафик исчерпан → блокировка (проверяется КАЖДЫЙ раз)
        if (user.isTrafficExhausted()) {
            // Уведомление — один раз
            if (!notified100Percent) {
                notified100Percent = true
                sendNotification(
                    id = NOTIF_ID_TRAFFIC_100,
                    title = getApplication<Application>().getString(R.string.notif_traffic_exhausted_title),
                    text = getApplication<Application>().getString(R.string.notif_traffic_exhausted_text)
                )
            }
            // Блокировка + отключение — каждый раз пока трафик исчерпан
            enforceAccessRestrictions(user)
        }
    }

    /**
     * Проверяет доступ пользователя (трафик + срок тарифа) и применяет ограничения.
     * Вызывается при подключении, мониторинге и перед запуском Авто.
     * Блокирует кнопку Авто и отключает VPN если доступ исчерпан.
     *
     * @return true если доступ разрешён, false если заблокирован
     */
    private fun enforceAccessRestrictions(user: User): Boolean {
        val app = getApplication<Application>()

        // Проверка трафика
        if (user.isTrafficExhausted()) {
            _autoBlocked.value = true
            _autoBlockReason.value = app.getString(R.string.notif_traffic_exhausted_title)
            _trafficExhausted.value = true
            if (_connectionState.value == VpnConnectionState.CONNECTED ||
                _connectionState.value == VpnConnectionState.CONNECTING
            ) {
                disconnectActive()
            }
            return false
        }

        // Проверка срока действия тарифа
        if (!user.hasActiveSubscription()) {
            _autoBlocked.value = true
            _autoBlockReason.value = app.getString(R.string.subscription_expired)
            if (_connectionState.value == VpnConnectionState.CONNECTED ||
                _connectionState.value == VpnConnectionState.CONNECTING
            ) {
                disconnectActive()
            }
            return false
        }

        // Доступ есть
        _autoBlocked.value = false
        _autoBlockReason.value = ""
        return true
    }

    /**
     * Публичная проверка доступа перед запуском Авто.
     * Синхронизирует трафик с сервером, проверяет лимит и срок.
     * @return TrafficCheckResult.Ok если можно запускать, иначе Blocked с причиной
     */
    suspend fun checkAccessBeforeAuto(): TrafficCheckResult {
        if (currentUserId == -1L) {
            currentUserId = sessionManager.getCurrentUser()?.id ?: -1L
        }
        val user = userDao.getUserById(currentUserId)
            ?: _currentUser.value
            ?: return TrafficCheckResult.Blocked("Пользователь не найден")

        // Для лимитных тарифов — синхронизируем с сервером перед проверкой
        val limit = user.effectiveTrafficLimit()
        if (limit != null && limit > 0L) {
            pushTrafficToServerIfLimited(user)
        }

        // Читаем свежие данные после синхронизации
        val fresh = userDao.getUserById(currentUserId) ?: user
        _currentUser.value = fresh

        val app = getApplication<Application>()
        if (fresh.isTrafficExhausted()) {
            _autoBlocked.value = true
            _autoBlockReason.value = app.getString(R.string.notif_traffic_exhausted_title)
            return TrafficCheckResult.Blocked(app.getString(R.string.notif_traffic_exhausted_text))
        }
        if (!fresh.hasActiveSubscription()) {
            _autoBlocked.value = true
            _autoBlockReason.value = app.getString(R.string.subscription_expired)
            return TrafficCheckResult.Blocked(app.getString(R.string.subscription_expired))
        }

        _autoBlocked.value = false
        _autoBlockReason.value = ""
        return TrafficCheckResult.Ok
    }

    private fun checkSubscriptionExpiry() {
        val user = _currentUser.value ?: return
        if (user.tariff == "Unlimited") return

        // Уведомление об истечении — один раз
        if (user.subscriptionExpiresWithin(SUBSCRIPTION_WARN_MS) && !notifiedExpiry) {
            notifiedExpiry = true
            val app = getApplication<Application>()
            sendNotification(
                id = NOTIF_ID_EXPIRY,
                title = app.getString(R.string.notif_expiry_title),
                text = app.getString(R.string.notif_expiry_text)
            )
        }

        // Истёк срок → блокировка (проверяется каждый раз)
        if (!user.hasActiveSubscription()) {
            enforceAccessRestrictions(user)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                getApplication<Application>().getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getApplication<Application>()
                    .getString(R.string.notif_channel_desc)
            }
            val manager = getApplication<Application>()
                .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun sendNotification(id: Int, title: String, text: String) {
        val app = getApplication<Application>()
        try {
            val notification = NotificationCompat.Builder(app, NOTIF_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_vpn_lock)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(app).notify(id, notification)
        } catch (e: SecurityException) {
            // Разрешение POST_NOTIFICATIONS не выдано — молча игнорируем
        }
    }

    /** Сбрасывает флаги уведомлений (вызывать при смене тарифа) */
    fun resetTrafficNotifications() {
        notified80Percent = false
        notified100Percent = false
        notifiedExpiry = false
        _trafficExhausted.value = false
        _autoBlocked.value = false
        _autoBlockReason.value = ""
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ПЕРИОДИЧЕСКАЯ СИНХРОНИЗАЦИЯ С СЕРВЕРОМ (каждый час)
    // ═══════════════════════════════════════════════════════════════════════

    private fun startPeriodicSync() {
        periodicSyncJob?.cancel()
        periodicSyncJob = viewModelScope.launch {
            // Первый запуск — сразу при старте (без задержки)
            syncUserDataFromServer()
            // Затем каждый час
            while (true) {
                delay(60L * 60L * 1000L)
                syncUserDataFromServer()
            }
        }
    }

    /**
     * Запрашивает актуальные данные пользователя с сервера:
     * тариф, subsEndDate, used_traffic, traffic_limit.
     * Обновляет БД и _currentUser.
     */
    suspend fun syncUserDataFromServer() {
        val user = sessionManager.getCurrentUser() ?: return
        val token = sessionManager.getAccessToken() ?: return
        try {
            authApiService.getUserData(user.email, token, userRepository)
            // getUserData уже обновляет БД и сессию — читаем свежие данные
            val updated = userDao.getUserById(user.id) ?: return
            _currentUser.value = updated
            sessionManager.updateCurrentUser(updated)
        } catch (e: Exception) {
            // Сеть недоступна — работаем с локальными данными
        }
    }
}

