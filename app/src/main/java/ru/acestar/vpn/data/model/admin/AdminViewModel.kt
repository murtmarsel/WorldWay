package ru.acestar.vpn.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import ru.acestar.vpn.api.AdminApiService
import ru.acestar.vpn.data.model.admin.*
import ru.acestar.vpn.data.repository.SessionManager

class AdminViewModel(application: Application) : AndroidViewModel(application) {

    private val apiService = AdminApiService(SessionManager.getInstance(application))

    // ─── States ──────────────────────────────────────────────────────────────

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

    // ─── Sealed classes ───────────────────────────────────────────────────────

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

    // ─── Auth ─────────────────────────────────────────────────────────────────

    fun adminLogin(password: String) {
        _authState.value = AuthState.Loading
        viewModelScope.launch {
            apiService.adminLogin(password)
                .onSuccess { token ->
                    _authState.value = AuthState.Success(token)
                    loadUsers()
                }
                .onFailure { _authState.value = AuthState.Error(it.message ?: "Login failed") }
        }
    }

    // ─── Users ────────────────────────────────────────────────────────────────

    fun loadUsers() {
        _usersState.value = UsersState.Loading
        viewModelScope.launch {
            apiService.getUsers()
                .onSuccess { _usersState.value = UsersState.Success(it) }
                .onFailure { _usersState.value = UsersState.Error(it.message ?: "Failed to load users") }
        }
    }

    fun selectUser(user: UserAdminData) { _selectedUser.value = user }

    // ─── Configs ──────────────────────────────────────────────────────────────

    fun loadUserConfigs(userId: Int) {
        _configsState.value = ConfigsState.Loading
        viewModelScope.launch {
            apiService.getClientFilesList(userId)
                .onSuccess { _configsState.value = ConfigsState.Success(it) }
                .onFailure { _configsState.value = ConfigsState.Error(it.message ?: "Failed") }
        }
    }

    // ─── Stats ────────────────────────────────────────────────────────────────

    fun loadUserStats(userId: Int) {
        _statsState.value = StatsState.Loading
        viewModelScope.launch {
            apiService.getUserStats(userId)
                .onSuccess { _statsState.value = StatsState.Success(it) }
                .onFailure { _statsState.value = StatsState.Error(it.message ?: "Failed") }
        }
    }

    /** Тихое обновление — не переводит в Loading, не вызывает мерцания UI */
    fun silentRefreshStats(userId: Int) {
        viewModelScope.launch {
            apiService.getUserStats(userId)
                .onSuccess { _statsState.value = StatsState.Success(it) }
                // при ошибке тихого обновления — не меняем состояние
        }
    }

    // ─── Payments ─────────────────────────────────────────────────────────────

    fun loadUserPayments(userId: Int) {
        _paymentsState.value = PaymentsState.Loading
        viewModelScope.launch {
            apiService.getUserPayments(userId)
                .onSuccess { _paymentsState.value = PaymentsState.Success(it) }
                .onFailure { _paymentsState.value = PaymentsState.Error(it.message ?: "Failed") }
        }
    }

    /** Тихое обновление платежей */
    fun silentRefreshPayments(userId: Int) {
        viewModelScope.launch {
            apiService.getUserPayments(userId)
                .onSuccess { _paymentsState.value = PaymentsState.Success(it) }
        }
    }

    // ─── Logs ─────────────────────────────────────────────────────────────────

    fun loadUserLogs(userId: Int) {
        _logsState.value = LogsState.Loading
        viewModelScope.launch {
            apiService.getUserLogs(userId)
                .onSuccess { _logsState.value = LogsState.Success(it) }
                .onFailure { _logsState.value = LogsState.Error(it.message ?: "Failed") }
        }
    }

    /** Тихое обновление логов */
    fun silentRefreshLogs(userId: Int) {
        viewModelScope.launch {
            apiService.getUserLogs(userId)
                .onSuccess { _logsState.value = LogsState.Success(it) }
        }
    }

    // ─── Update user ──────────────────────────────────────────────────────────

    fun updateUser(
        userId: Int,
        email: String? = null,
        tariff: String? = null,
        emailConfirmed: Boolean? = null,
        isActive: Boolean? = null,
        usedTraffic: Long? = null,
        trafficLimit: Long? = null
    ) {
        _operationState.value = OperationState.Loading
        viewModelScope.launch {
            val req = UpdateUserRequest(
                email = email,
                tariff = tariff,
                email_confirmed = emailConfirmed,
                is_active = isActive,
                used_traffic = usedTraffic,
                traffic_limit = trafficLimit
            )
            apiService.updateUser(userId, req)
                .onSuccess {
                    _operationState.value = OperationState.Success("Пользователь обновлён")
                    loadUsers()
                }
                .onFailure { _operationState.value = OperationState.Error(it.message ?: "Update failed") }
        }
    }

    // ─── Reset password ───────────────────────────────────────────────────────

    fun resetPassword(userId: Int, newPassword: String, userEmail: String) {
        _operationState.value = OperationState.Loading
        viewModelScope.launch {
            apiService.resetPassword(userId, newPassword, userEmail)
                .onSuccess { _operationState.value = OperationState.Success("Пароль сброшен") }
                .onFailure { _operationState.value = OperationState.Error(it.message ?: "Reset failed") }
        }
    }

    fun sendPasswordResetEmail(userId: Int, email: String) {
        _operationState.value = OperationState.Loading
        viewModelScope.launch {
            apiService.sendPasswordResetEmail(userId, email)
                .onSuccess { _operationState.value = OperationState.Success("Письмо отправлено на $email") }
                .onFailure { _operationState.value = OperationState.Error(it.message ?: "Failed to send email") }
        }
    }

    // ─── Delete user ──────────────────────────────────────────────────────────

    fun deleteUser(userId: Int) {
        _operationState.value = OperationState.Loading
        viewModelScope.launch {
            apiService.deleteUser(userId)
                .onSuccess {
                    _operationState.value = OperationState.Success("Пользователь удалён")
                    loadUsers()
                    _selectedUser.value = null
                }
                .onFailure { _operationState.value = OperationState.Error(it.message ?: "Delete failed") }
        }
    }

    // ─── Subscription ─────────────────────────────────────────────────────────

    fun updateSubscription(userId: Int, action: String, tariff: String? = null) {
        _operationState.value = OperationState.Loading
        viewModelScope.launch {
            apiService.updateSubscription(userId, action, tariff)
                .onSuccess {
                    val msg = when (action) {
                        "pause"  -> "Подписка приостановлена"
                        "resume" -> "Подписка возобновлена"
                        "cancel" -> "Подписка отменена (Trial)"
                        else     -> "Подписка обновлена"
                    }
                    _operationState.value = OperationState.Success(msg)
                    loadUsers()
                }
                .onFailure { _operationState.value = OperationState.Error(it.message ?: "Failed") }
        }
    }

    // ─── Traffic ──────────────────────────────────────────────────────────────

    fun updateTraffic(userId: Int, deltaBytes: Long, reset: Boolean = false) {
        _operationState.value = OperationState.Loading
        viewModelScope.launch {
            apiService.updateTraffic(userId, deltaBytes, reset)
                .onSuccess {
                    val msg = if (reset) "Трафик сброшен" else "Трафик обновлён"
                    _operationState.value = OperationState.Success(msg)
                    loadUsers()
                }
                .onFailure { _operationState.value = OperationState.Error(it.message ?: "Failed") }
        }
    }

    // ─── Misc ─────────────────────────────────────────────────────────────────

    fun resetStates() {
        _operationState.value = OperationState.Idle
    }
}
