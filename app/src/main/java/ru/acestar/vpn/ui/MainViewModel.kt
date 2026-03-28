package ru.acestar.vpn.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import ru.acestar.vpn.VpnApplication
import ru.acestar.vpn.data.model.User
import ru.acestar.vpn.data.model.VpnConfigEntity
import ru.acestar.vpn.data.repository.SessionManager
import ru.acestar.vpn.data.repository.UserRepository
import ru.acestar.vpn.vpn.OpenVpnManager
import ru.acestar.vpn.vpn.VpnConnectionState

class MainViewModel(application: Application) : AndroidViewModel(application) {

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
        observeVpnConfigs()
        ensureServerPlaceholders()
    }
    private fun loadCurrentUser() {
        viewModelScope.launch {
            val user = sessionManager.getCurrentUser()
            val userId = user?.id ?: -1
            if (userId != -1L) {
                val user = userRepository.getUserById(userId)
                _currentUser.value = user
                sessionManager.updateCurrentUser(user)
            }
        }
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
            if (vpnConfigDao.countBySource(currentUserId, source) == 0) {
                vpnConfigDao.insert(
                    VpnConfigEntity(
                        userId = currentUserId,
                        name = "Сервер 1",
                        serverHost = "-",
                        configContent = "",
                        source = source
                    )
                )
                vpnConfigDao.insert(
                    VpnConfigEntity(
                        userId = currentUserId,
                        name = "Сервер 2",
                        serverHost = "-",
                        configContent = "",
                        source = source
                    )
                )
            }
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
