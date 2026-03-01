package ru.acestar.vpn.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.acestar.vpn.VpnApplication
import ru.acestar.vpn.data.model.User
import ru.acestar.vpn.data.repository.SessionManager
import ru.acestar.vpn.data.repository.UserRepository
import ru.acestar.vpn.vpn.OpenVpnManager
import ru.acestar.vpn.vpn.VpnConnectionState

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val database = (application as VpnApplication).database
    private val userRepository = UserRepository(database.userDao())
    private val sessionManager = SessionManager.getInstance(application)
    val vpnManager = OpenVpnManager.getInstance(application)

    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()

    private val _configLoaded = MutableStateFlow(false)
    val configLoaded: StateFlow<Boolean> = _configLoaded.asStateFlow()

    val connectionState: StateFlow<VpnConnectionState> = vpnManager.connectionState
    val statusMessage: StateFlow<String> = vpnManager.statusMessage

    init {
        loadCurrentUser()
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
}
