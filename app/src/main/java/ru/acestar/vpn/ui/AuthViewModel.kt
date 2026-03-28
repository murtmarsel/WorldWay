package ru.acestar.vpn.ui

import android.app.Activity.RESULT_OK
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.acestar.vpn.VpnApplication
import ru.acestar.vpn.api.AuthApiService
import ru.acestar.vpn.data.model.User
import ru.acestar.vpn.data.repository.SessionManager
import ru.acestar.vpn.data.repository.UserRepository
import ru.acestar.vpn.utils.PasswordHasher
import java.sql.Date as SqlDate

sealed class AuthState {
    object Idle : AuthState()
    object Loading : AuthState()
    data class Success(val user: User) : AuthState()
    data class Error(val message: String) : AuthState()
}

class AuthViewModel(application: Application) : AndroidViewModel(application) {
    
    private val authApiService = AuthApiService()
    private val sessionManager = SessionManager.getInstance(application)
    private val userRepository = UserRepository((application as VpnApplication).database.userDao())
    
    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()
    
    fun login(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            _authState.value = AuthState.Error("Заполните все поля")
            return
        }
        
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            
            val emailTrim = email.trim()
            val passwordHash = PasswordHasher.hashForServer(password, emailTrim)
            val result = authApiService.login(emailTrim, passwordHash)
            result.fold(
                onSuccess = { authData ->
                    val resolvedEmail = authData.email.ifBlank { emailTrim }
                    val user = userRepository.upsertAfterServerAuth(resolvedEmail, passwordHash, authData)
                    sessionManager.saveSession(
                        user = user,
                        accessToken = authData.token,
                        email = resolvedEmail
                    )
                    _authState.value = AuthState.Success(user)
                },
                onFailure = { exception ->
                    _authState.value = AuthState.Error(exception.message ?: "Ошибка входа")
                }
            )
        }
    }
    fun fields_check(email: String, password: String, confirmPassword: String): Boolean {
        if (email.isBlank() || password.isBlank()) {
            _authState.value = AuthState.Error("Заполните все поля")

            return false
        }

        if (password != confirmPassword) {
            _authState.value = AuthState.Error("Пароли не совпадают")
            return false
        }

        if (password.length < 6) {
            _authState.value = AuthState.Error("Пароль должен быть не менее 6 символов")
            return false
        }

        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            _authState.value = AuthState.Error("Некорректный email")
            return false
        }
        return true
    }
    fun register(email: String, password: String, confirmPassword: String) {
        if (!fields_check(email, password, confirmPassword)) return

        viewModelScope.launch {
            _authState.value = AuthState.Loading
            
            val result = authApiService.createUser(
                email = email.trim(),
                password = password,
                isConfirmed = false,
                createDate = SqlDate(System.currentTimeMillis()),
                tariff = "Free",
                isNew = true
            )
            result.fold(
                onSuccess = { authData ->
                    val emailTrim = authData.email.ifBlank { email.trim() }
                    val passwordHash = PasswordHasher.hashForServer(password, emailTrim)
                    val user = userRepository.upsertAfterServerAuth(emailTrim, passwordHash, authData)
                    sessionManager.saveSession(
                        user = user,
                        accessToken = authData.token,
                        email = emailTrim
                    )
                    _authState.value = AuthState.Success(user)
                },
                onFailure = { exception ->
                    _authState.value = AuthState.Error(exception.message ?: "Ошибка регистрации")
                }
            )
        }
    }
    
    fun resetState() {
        _authState.value = AuthState.Idle
    }
    
    suspend fun isLoggedIn(): Boolean = sessionManager.isLoggedIn()

}
