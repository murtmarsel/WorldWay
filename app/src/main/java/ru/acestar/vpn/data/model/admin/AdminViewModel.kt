// viewmodel/AdminViewModel.kt
package ru.acestar.vpn.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import ru.acestar.vpn.api.AdminApiService
import ru.acestar.vpn.data.repository.SessionManager
import ru.acestar.vpn.data.model.admin.UpdateUserRequest
import ru.acestar.vpn.data.model.admin.UserAdminData
import ru.acestar.vpn.data.model.admin.UserConfigFile
import ru.acestar.vpn.data.model.admin.UserLog
import ru.acestar.vpn.data.model.admin.UserPayment
import ru.acestar.vpn.data.model.admin.UserStats

class AdminViewModel(application: Application) : AndroidViewModel(application) {

    private val apiService = AdminApiService(SessionManager.getInstance(application))

    // Состояния
    private val _authState = MutableLiveData<AuthState>(AuthState.Idle)
    val authState: LiveData<AuthState> = _authState

    private val _usersState = MutableLiveData<UsersState>(UsersState.Idle)
    val usersState: LiveData<UsersState> = _usersState

    private val _selectedUser = MutableLiveData<UserAdminData?>()
    val selectedUser: LiveData<UserAdminData?> = _selectedUser

    private val _configsState = MutableLiveData<ConfigsState>(ConfigsState.Idle)
    val configsState: LiveData<ConfigsState> = _configsState

    private val _statsState = MutableLiveData<StatsState>(StatsState.Idle)
    val statsState: LiveData<StatsState> = _statsState

    private val _paymentsState = MutableLiveData<PaymentsState>(PaymentsState.Idle)
    val paymentsState: LiveData<PaymentsState> = _paymentsState

    private val _logsState = MutableLiveData<LogsState>(LogsState.Idle)
    val logsState: LiveData<LogsState> = _logsState

    private val _operationState = MutableLiveData<OperationState>(OperationState.Idle)
    val operationState: LiveData<OperationState> = _operationState

    sealed class AuthState {
        object Idle : AuthState()
        object Loading : AuthState()
        data class Success(val token: String) : AuthState()
        data class Error(val message: String) : AuthState()
    }

    sealed class UsersState {
        object Idle : UsersState()
        object Loading : UsersState()
        data class Success(val users: List<UserAdminData>) : UsersState()
        data class Error(val message: String) : UsersState()
    }

    sealed class ConfigsState {
        object Idle : ConfigsState()
        object Loading : ConfigsState()
        data class Success(val configs: List<UserConfigFile>) : ConfigsState()
        data class Error(val message: String) : ConfigsState()
    }

    sealed class StatsState {
        object Idle : StatsState()
        object Loading : StatsState()
        data class Success(val stats: UserStats) : StatsState()
        data class Error(val message: String) : StatsState()
    }

    sealed class PaymentsState {
        object Idle : PaymentsState()
        object Loading : PaymentsState()
        data class Success(val payments: List<UserPayment>) : PaymentsState()
        data class Error(val message: String) : PaymentsState()
    }

    sealed class LogsState {
        object Idle : LogsState()
        object Loading : LogsState()
        data class Success(val logs: List<UserLog>) : LogsState()
        data class Error(val message: String) : LogsState()
    }

    sealed class OperationState {
        object Idle : OperationState()
        object Loading : OperationState()
        data class Success(val message: String) : OperationState()
        data class Error(val message: String) : OperationState()
    }

    fun adminLogin(password: String) {
        _authState.value = AuthState.Loading
        viewModelScope.launch {
            val result = apiService.adminLogin(password)
            result.onSuccess { token ->
//                apiService.getToken()
                _authState.value = AuthState.Success(token)
                loadUsers()
            }.onFailure { exception ->
                _authState.value = AuthState.Error(exception.message ?: "Login failed")
            }
        }
    }

    fun loadUsers() {
        _usersState.value = UsersState.Loading
        viewModelScope.launch {
            val result = apiService.getUsers()
            result.onSuccess { users ->
                _usersState.value = UsersState.Success(users)
            }.onFailure { exception ->
                _usersState.value = UsersState.Error(exception.message ?: "Failed to load users")
            }
        }
    }

    fun selectUser(user: UserAdminData) {
        _selectedUser.value = user
    }

    fun loadUserConfigs(userId: Int) {
        _configsState.value = ConfigsState.Loading
        viewModelScope.launch {
            val result = apiService.getClientFilesList(userId)
            result.onSuccess { configs ->
                _configsState.value = ConfigsState.Success(configs)
            }.onFailure { exception ->
                _configsState.value = ConfigsState.Error(exception.message ?: "Failed to load configs")
            }
        }
    }

    fun loadUserStats(userId: Int) {
        _statsState.value = StatsState.Loading
        viewModelScope.launch {
            val result = apiService.getUserStats(userId)
            result.onSuccess { stats ->
                _statsState.value = StatsState.Success(stats)
            }.onFailure { exception ->
                _statsState.value = StatsState.Error(exception.message ?: "Failed to load stats")
            }
        }
    }

    fun loadUserPayments(userId: Int) {
        _paymentsState.value = PaymentsState.Loading
        viewModelScope.launch {
            val result = apiService.getUserPayments(userId)
            result.onSuccess { payments ->
                _paymentsState.value = PaymentsState.Success(payments)
            }.onFailure { exception ->
                _paymentsState.value = PaymentsState.Error(exception.message ?: "Failed to load payments")
            }
        }
    }

    fun loadUserLogs(userId: Int) {
        _logsState.value = LogsState.Loading
        viewModelScope.launch {
            val result = apiService.getUserLogs(userId)
            result.onSuccess { logs ->
                _logsState.value = LogsState.Success(logs)
            }.onFailure { exception ->
                _logsState.value = LogsState.Error(exception.message ?: "Failed to load logs")
            }
        }
    }

    fun updateUser(userId: Int, email: String? = null, tariff: String? = null, emailConfirmed: Boolean? = null, isActive: Boolean? = null) {
        _operationState.value = OperationState.Loading
        viewModelScope.launch {
            val request = UpdateUserRequest(
                email = email,
                tariff = tariff,
                email_confirmed = emailConfirmed,
                is_active = isActive
            )
            val result = apiService.updateUser(userId, request)
            result.onSuccess {
                _operationState.value = OperationState.Success("User updated successfully")
                loadUsers()
                _selectedUser.value?.let { user ->
                    loadUserStats(user.id)
                }
            }.onFailure { exception ->
                _operationState.value = OperationState.Error(exception.message ?: "Update failed")
            }
        }
    }

    fun resetPassword(userId: Int, newPassword: String) {
        _operationState.value = OperationState.Loading
        viewModelScope.launch {
            val result = apiService.resetPassword(userId, newPassword)
            result.onSuccess {
                _operationState.value = OperationState.Success("Password reset successfully")
            }.onFailure { exception ->
                _operationState.value = OperationState.Error(exception.message ?: "Reset failed")
            }
        }
    }

    fun deleteUser(userId: Int) {
        _operationState.value = OperationState.Loading
        viewModelScope.launch {
            val result = apiService.deleteUser(userId)
            result.onSuccess {
                _operationState.value = OperationState.Success("User deleted successfully")
                loadUsers()
                _selectedUser.value = null
            }.onFailure { exception ->
                _operationState.value = OperationState.Error(exception.message ?: "Delete failed")
            }
        }
    }

    fun updateSubscription(userId: Int, action: String, tariff: String? = null) {
        _operationState.value = OperationState.Loading
        viewModelScope.launch {
            val result = apiService.updateSubscription(userId, action, tariff)
            result.onSuccess {
                val message = when (action) {
                    "pause" -> "Subscription paused"
                    "resume" -> "Subscription resumed"
                    "cancel" -> "Subscription cancelled"
                    else -> "Subscription updated"
                }
                _operationState.value = OperationState.Success(message)
                loadUsers()
                _selectedUser.value?.let { user ->
                    loadUserStats(user.id)
                }
            }.onFailure { exception ->
                _operationState.value = OperationState.Error(exception.message ?: "Update failed")
            }
        }
    }

    fun resetStates() {
        _operationState.value = OperationState.Idle
    }
}