package ru.acestar.vpn.data.repository

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.acestar.vpn.data.model.User

class SessionManager(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )
    
    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()
    
    companion object {
        private const val PREFS_NAME = "vpn_session"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
        
        @Volatile
        private var INSTANCE: SessionManager? = null
        
        fun getInstance(context: Context): SessionManager {
            return INSTANCE ?: synchronized(this) {
                val instance = SessionManager(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
    
    fun saveSession(user: User) {
        prefs.edit()
            .putLong(KEY_USER_ID, user.id)
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .apply()
        _currentUser.value = user
    }
    
    fun clearSession() {
        prefs.edit()
            .remove(KEY_USER_ID)
            .putBoolean(KEY_IS_LOGGED_IN, false)
            .apply()
        _currentUser.value = null
    }
    
    fun isLoggedIn(): Boolean {
        return prefs.getBoolean(KEY_IS_LOGGED_IN, false)
    }
    
    fun getUserId(): Long {
        return prefs.getLong(KEY_USER_ID, -1)
    }
    
    fun updateCurrentUser(user: User?) {
        _currentUser.value = user
    }
    
    fun hasActiveSubscription(): Boolean {
        val user = _currentUser.value ?: return false
        return user.hasActiveSubscription()
    }
}
