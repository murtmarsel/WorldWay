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

    private val database = (application as VpnApplication).database
    private val userRepository = UserRepository(database.userDao())
    private val vpnConfigDao = database.vpnConfigDao()
    private val sessionManager = SessionManager.getInstance(application)
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
            val userId = sessionManager.getUserId()
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

    fun hasActiveSubscription(): Boolean {
        return _currentUser.value?.hasActiveSubscription() == true
    }

    fun logout() {
        sessionManager.clearSession()
        _currentUser.value = null
    }

    fun refreshUser() {
        loadCurrentUser()
    }

    private fun observeVpnConfigs() {
        viewModelScope.launch {
            vpnConfigDao.observeAll().collectLatest { configs ->
                _vpnConfigs.value = configs
            }
        }
    }

    private fun ensureServerPlaceholders() {
        viewModelScope.launch {
            val source = "server_placeholder"
            if (vpnConfigDao.countBySource(source) == 0) {
                vpnConfigDao.insert(
                    VpnConfigEntity(
                        name = "Сервер 1",
                        serverHost = "-",
                        configContent = "",
                        source = source
                    )
                )
                vpnConfigDao.insert(
                    VpnConfigEntity(
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
        return vpnConfigDao.insert(
            VpnConfigEntity(
                name = name,
                serverHost = serverHost,
                configContent = configContent,
                source = "manual"
            )
        )
    }

    suspend fun saveServerConfig(name: String, serverHost: String, configContent: String): Long {
        return vpnConfigDao.insert(
            VpnConfigEntity(
                name = name,
                serverHost = serverHost,
                configContent = configContent,
                source = "server"
            )
        )
    }

    suspend fun getConfigById(id: Long): VpnConfigEntity? {
        return vpnConfigDao.getById(id)
    }
}
