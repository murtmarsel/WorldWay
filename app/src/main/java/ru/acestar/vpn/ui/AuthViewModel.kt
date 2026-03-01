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

sealed class AuthState {
    object Idle : AuthState()
    object Loading : AuthState()
    data class Success(val user: User) : AuthState()
    data class Error(val message: String) : AuthState()
}

class AuthViewModel(application: Application) : AndroidViewModel(application) {
    
    private val database = (application as VpnApplication).database
    private val userRepository = UserRepository(database.userDao())
    private val sessionManager = SessionManager.getInstance(application)
    
    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()
    
    fun login(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            _authState.value = AuthState.Error("Заполните все поля")
            return
        }
        
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            
            val result = userRepository.login(email.trim(), password)
            result.fold(
                onSuccess = { user ->
                    sessionManager.saveSession(user)
                    _authState.value = AuthState.Success(user)
                },
                onFailure = { exception ->
                    _authState.value = AuthState.Error(exception.message ?: "Ошибка входа")
                }
            )
        }
    }
    
    fun register(username: String, email: String, password: String, confirmPassword: String) {
        if (username.isBlank() || email.isBlank() || password.isBlank()) {
            _authState.value = AuthState.Error("Заполните все поля")
            return
        }
        
        if (password != confirmPassword) {
            _authState.value = AuthState.Error("Пароли не совпадают")
            return
        }
        
        if (password.length < 6) {
            _authState.value = AuthState.Error("Пароль должен быть не менее 6 символов")
            return
        }
        
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            _authState.value = AuthState.Error("Некорректный email")
            return
        }
        
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            
            val result = userRepository.register(email.trim(), password, username.trim())
            result.fold(
                onSuccess = { user ->
                    sessionManager.saveSession(user)
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
    
    fun isLoggedIn(): Boolean {
        return sessionManager.isLoggedIn()
    }
}
