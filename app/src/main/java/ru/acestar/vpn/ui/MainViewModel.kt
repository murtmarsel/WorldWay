package ru.acestar.vpn.ui

import android.app.Application
import android.widget.Toast
import androidx.activity.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.acestar.vpn.R
import ru.acestar.vpn.VpnApplication
import ru.acestar.vpn.data.dao.VpnServerConfigDao
import ru.acestar.vpn.data.model.User
import ru.acestar.vpn.data.model.VpnConfigEntity
import ru.acestar.vpn.data.model.VpnServerConfigEntity
import ru.acestar.vpn.data.repository.SessionManager
import ru.acestar.vpn.data.repository.UserRepository
import ru.acestar.vpn.vpn.OpenVpnManager
import ru.acestar.vpn.vpn.VpnConnectionState
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.getValue

class MainViewModel(application: Application) : AndroidViewModel(application) {


    private val _connectionAttemptState = MutableStateFlow<ConnectionAttemptState>(ConnectionAttemptState.Idle)
    val connectionAttemptState: StateFlow<ConnectionAttemptState> = _connectionAttemptState.asStateFlow()
    private var _isServerConfigsLoaded = MutableStateFlow(false)
    var isServerConfigsLoaded: StateFlow<Boolean> = _isServerConfigsLoaded.asStateFlow()

    private var configsQueue: List<VpnServerConfigEntity> = emptyList()

    private val vpnServerConfigDao = (application as VpnApplication).database.vpnServerConfigDao()
    private var currentAttemptIndex = 0
    private var isAttemptingConnection = false

    sealed class ConnectionAttemptState {
        object Idle : ConnectionAttemptState()
        data class Trying(val current: Int, val total: Int, val configName: String) : ConnectionAttemptState()
        data class Success(val config: VpnServerConfigEntity) : ConnectionAttemptState()
        data class Failed(val message: String) : ConnectionAttemptState()
        object AllFailed : ConnectionAttemptState()
    }


    companion object {
        /** Показать напоминание о подписке, если осталось меньше 7 дней. */
        const val SUBSCRIPTION_WARN_MS = 7L * 24L * 60L * 60L * 1000L
    }


    private val database = (application as VpnApplication).database
    private val userRepository = UserRepository(database.userDao())
    private val vpnConfigDao = database.vpnConfigDao()
    private val sessionManager = SessionManager.getInstance(application)
    private var currentUserId = -1L
    val vpnManager = OpenVpnManager.getInstance(application)

    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()

    private val _configLoaded = MutableStateFlow(false)
    val configLoaded: StateFlow<Boolean> = _configLoaded.asStateFlow()

    val connectionState: StateFlow<VpnConnectionState> = vpnManager.connectionState
    val statusMessage: StateFlow<String> = vpnManager.statusMessage
    val byteCountIn: StateFlow<Long> = vpnManager.byteCountIn
    val byteCountOut: StateFlow<Long> = vpnManager.byteCountOut
    val isPaused: StateFlow<Boolean> = vpnManager.isPaused

    private val _vpnConfigs = MutableStateFlow<List<VpnConfigEntity>>(emptyList())
    val vpnConfigs: StateFlow<List<VpnConfigEntity>> = _vpnConfigs.asStateFlow()

    init {
        loadCurrentUser()
    }
    private fun loadCurrentUser() {
        viewModelScope.launch {
            val user = sessionManager.getCurrentUser()
            val userId = user?.id ?: -1
            currentUserId = userId
            observeVpnConfigs()
            ensureServerPlaceholders()
            if (userId != -1L) {
                val user = userRepository.getUserById(userId)
                _currentUser.value = user
                sessionManager.updateCurrentUser(user)
            }
        }
    }
    private fun updateCurrentUser(){
        viewModelScope.launch {

            currentUserId
        }
    }
    suspend fun loadServerConfigsAndWait(userId: Long): Boolean {
        return suspendCoroutine { continuation ->
            var finished = false

            // Подписываемся на изменения
            val job = viewModelScope.launch {
                var res = isServerConfigsLoaded.first { it }

                println("isServerConfigsLoaded: ${res}")
                if (res && !finished) {
                    finished = true
                    continuation.resume(true)
                }

            }

            // Запускаем загрузку
            connectToFirstAvailable(userId) {
                if (!finished) {
                    finished = true
                    job.cancel()
                    continuation.resume(false)
                }
            }


            // Таймаут на всякий случай
            viewModelScope.launch {
//                delay(6000) // 30 секунд таймаут
                if (!finished) {
                    finished = true
                    job.cancel()
                    continuation.resume(false)
                }
            }
        }
    }

    /**
     * Получение всех конфигураций в порядке приоритета
     */
//    fun getConfigsInOrder(userId: Long): List<VpnServerConfigEntity> {
//        return vpnServerConfigDao.observeAllByOrder(userId)
//    }

    /**
     * Подключение к первой доступной конфигурации
     */
    fun connectToFirstAvailable(userId: Long, onAllFailed: () -> Unit) {
        if (isAttemptingConnection) {
            println("⚠️ Уже выполняется подключение")
            return
        }

        viewModelScope.launch {
            isAttemptingConnection = true
            _connectionAttemptState.value = ConnectionAttemptState.Idle

            // Получаем конфигурации в порядке приоритета
            vpnServerConfigDao.observeAllByOrder(userId).collect { configs ->
                if (configs.isEmpty()) {
                    _connectionAttemptState.value = ConnectionAttemptState.Failed("Нет доступных конфигураций")
                    isAttemptingConnection = false
                    onAllFailed()
                    isServerConfigsLoaded = MutableStateFlow(false)
                    println("Конфигации загружены? (false): ${isServerConfigsLoaded.value}")
                    return@collect

                }

                isServerConfigsLoaded = MutableStateFlow(true)
                println("Конфигации загружены? (true): ${isServerConfigsLoaded.value}")

                configsQueue = configs
                currentAttemptIndex = 0

                // Начинаем попытку подключения
                tryConnectNextConfig()

                // Прерываем сбор, так как нам нужна только одна попытка
                return@collect
            }
        }
    }

    /**
     * Попытка подключения к следующей конфигурации
     */
    fun tryConnectNextConfig() {
        if (currentAttemptIndex >= configsQueue.size) {
            // Все конфигурации перебраны, ни одна не подошла
            _connectionAttemptState.value = ConnectionAttemptState.AllFailed
            isAttemptingConnection = false
            onAllConfigsFailed()
            return
        }

        val config = configsQueue[currentAttemptIndex]
        _connectionAttemptState.value = ConnectionAttemptState.Trying(
            current = currentAttemptIndex + 1,
            total = configsQueue.size,
            configName = config.name
        )

        // Пытаемся подключиться
        attemptConnection(config)
    }

    /**
     * Попытка подключения к конкретной конфигурации
     */
    private fun attemptConnection(config: VpnServerConfigEntity) {
        viewModelScope.launch {
            try {
                val configName = "${currentAttemptIndex}"
                // Загружаем конфигурацию
                val success = loadConfig(config.content, configName)

                if (success) {
                    // Проверяем подключение
                    val connectionResult = checkConnection()

                    if (connectionResult) {
                        // Подключение успешно
                        _connectionAttemptState.value = ConnectionAttemptState.Success(config)
                        isAttemptingConnection = false
                        return@launch
                    } else {
                        // Подключение не удалось, переходим к следующему
                        println("❌ Не удалось подключиться к ${config.name}, пробуем следующий...")
                        currentAttemptIndex++
                        tryConnectNextConfig()
                    }
                } else {
                    // Ошибка загрузки конфигурации
                    println("❌ Ошибка загрузки конфигурации ${config.name}")
                    currentAttemptIndex++
                    tryConnectNextConfig()
                }
            } catch (e: Exception) {
                println("❌ Исключение при подключении к ${config.name}: ${e.message}")
                currentAttemptIndex++
                tryConnectNextConfig()
            }
        }
    }

    private fun loadConfig(content: String, serverName: String): Boolean {
        val success = vpnManager.loadConfigFromInputStream(
            ByteArrayInputStream(content.toByteArray()),
            serverName
        )
        if (success) {
            onConfigLoaded()
            return true
//            showToast(R.string.config_loaded)
        } else {
//            showToast("Ошибка загрузки конфигурации")
            return false
        }
    }
    /**
     * Проверка подключения (например, пинг до сервера)
     */
    private suspend fun checkConnection(): Boolean {
        try {
            // Здесь логика проверки подключения
            // Например, пинг до 8.8.8.8 или до VPN сервера
            val process = Runtime.getRuntime().exec("ping -c 1 -W 2 8.8.8.8")
            val exitCode = process.waitFor()
            exitCode == 0
            return true
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Обработка случая, когда все конфигурации не работают
     */
    private fun onAllConfigsFailed() {
        println("❌ Все конфигурации не работают")
        // Вызываем отдельную функцию
        showNoConnectionAvailable()
    }

    private fun showNoConnectionAvailable() {
        // Ваша логика при отсутствии подключения
        // Например, показать диалог или уведомление
    }

    fun resetAttemptState() {
        isAttemptingConnection = false
        _connectionAttemptState.value = ConnectionAttemptState.Idle
    }




    fun onConfigLoaded() {
        _configLoaded.value = true
    }

    fun clearConfigLoaded() {
        _configLoaded.value = false
    }

    fun hasActiveSubscription(): Boolean {
        return _currentUser.value?.hasActiveSubscription() == true
    }

    /** Почта не подтверждена по данным локального профиля. */
    fun shouldShowEmailUnconfirmedBanner(): Boolean {
        return _currentUser.value?.let { !it.emailConfirmed } == true
    }

    /** Нет подписки или скоро истечёт (в пределах [SUBSCRIPTION_WARN_MS]). */
    fun shouldShowSubscriptionWarningBanner(): Boolean {
        val u = _currentUser.value ?: return false
        if (!u.hasActiveSubscription()) return true
        return u.subscriptionExpiresWithin(SUBSCRIPTION_WARN_MS)
    }

//    fun logout() {
//        sessionManager.clearSession()
//        _currentUser.value = null
//    }

    fun refreshUser() {
        loadCurrentUser()
    }

    private fun observeVpnConfigs() {
        if (currentUserId == -1L) return
        viewModelScope.launch {
            vpnConfigDao.observeAllByUser(currentUserId).collectLatest { configs ->
                _vpnConfigs.value = configs
            }
        }
    }

    private fun ensureServerPlaceholders() {
        if (currentUserId == -1L) return
        viewModelScope.launch {
            val source = "server_placeholder"
//            println("СС:${vpnConfigDao.countBySource(currentUserId, source)}")
//            if (vpnConfigDao.countBySource(currentUserId, source) == 0) {
//                vpnConfigDao.insert(
//                    VpnConfigEntity(
//                        userId = currentUserId,
//                        name = "Сервер 1",
//                        serverHost = "-",
//                        configContent = "",
//                        source = source
//                    )
//                )
//                vpnConfigDao.insert(
//                    VpnConfigEntity(
//                        userId = currentUserId,
//                        name = "Сервер 2",
//                        serverHost = "-",
//                        configContent = "",
//                        source = source
//                    )
//                )
//            }
        }
    }

    suspend fun saveManualConfig(name: String, serverHost: String, configContent: String): Long {
        if (currentUserId == -1L) return -1L
        return vpnConfigDao.insert(
            VpnConfigEntity(
                userId = currentUserId,
                name = name,
                serverHost = serverHost,
                configContent = configContent,
                source = "manual"
            )
        )
    }

    suspend fun saveServerConfig(name: String, serverHost: String, configContent: String): Long {
        if (currentUserId == -1L) return -1L
        return vpnConfigDao.insert(
            VpnConfigEntity(
                userId = currentUserId,
                name = name,
                serverHost = serverHost,
                configContent = configContent,
                source = "server"
            )
        )
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
