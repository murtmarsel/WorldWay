// data/repository/SessionManager.kt
package ru.acestar.vpn.data.repository

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import ru.acestar.vpn.data.database.AppDatabase
import ru.acestar.vpn.data.model.CurrentSession
import ru.acestar.vpn.data.model.Session
import ru.acestar.vpn.data.model.User
import ru.acestar.vpn.utils.JwtTokenUtils

class SessionManager private constructor(
    private val context: Context
) {
    private val database = AppDatabase.getDatabase(context)
    private val sessionDao = database.sessionDao()

    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()

    val currentSession: Flow<CurrentSession?> = sessionDao.getCurrentSession()

    companion object {
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

    init {
        CoroutineScope(Dispatchers.IO).launch {
            sessionDao.getCurrentSession().collect { session: CurrentSession? ->
                val now = System.currentTimeMillis()
                val validSession = session?.takeIf {
                    it.tokenExpiry > now && !it.token.isNullOrBlank()
                }

                validSession?.let {
                    val user = User(
                        id = it.userId,
                        email = it.email,
                        isAdmin = it.isAdmin,
                        isNew = it.isNew,
                        isLoggedIn = true,
                        subsEndDate = it.subsEndDate,
                        isActive = it.isActive,
                        createdAt = it.createdAt,
                        tariff = it.tariff,
                        emailConfirmed = it.isConfirmed,
                        usedTrafficBytes = it.usedTrafficBytes,
                        trafficLimit = it.trafficLimit
                    )
                    _currentUser.value = user
                } ?: run { _currentUser.value = null }
            }
        }

        // Очищаем истекшие сессии
        CoroutineScope(Dispatchers.IO).launch {
            sessionDao.deleteExpiredSessions(System.currentTimeMillis())
        }
    }

    suspend fun updateCurrentSession(currentSession: CurrentSession) {
        sessionDao.updateCurrentSession(
            currentSession.userId, currentSession.email,
            currentSession.tariff, currentSession.subsEndDate,
            currentSession.isConfirmed,
            currentSession.isActive, currentSession.isAdmin,
            currentSession.isNew, currentSession.usedTrafficBytes,
            currentSession.trafficLimit
        )
    }

    suspend fun saveSession(
        id: Long,
        user: User,
        email: String? = null,
        token: String?,
        tariff: String,
        subsEndDate: Long,
        isConfirmed: Boolean,
        isActive: Boolean,
        createdAt: Long,
        usedTrafficBytes: Long = user.usedTrafficBytes,
        trafficLimit: Long = user.trafficLimit,
        expiresInSeconds: Long = 30 * 24 * 60 * 60,
    ) {
        val now = System.currentTimeMillis()
        val existingCurrent = sessionDao.getCurrentSessionSync()

        val normalizedToken = token?.takeIf { it.isNotBlank() }
        val tokenToSave = normalizedToken ?: existingCurrent?.token
        val tokenExpiryFromToken = normalizedToken?.let { JwtTokenUtils.extractExpiryMillis(it) }
        val tokenExpiryToSave = when {
            tokenToSave.isNullOrBlank() -> 0L
            tokenExpiryFromToken != null -> tokenExpiryFromToken
            normalizedToken != null -> now + (expiresInSeconds * 1000)
            else -> existingCurrent?.tokenExpiry ?: 0L
        }

        sessionDao.saveCurrentSession(
            CurrentSession(
                userId = id,
                email = user.email,
                token = tokenToSave,
                tokenExpiry = tokenExpiryToSave,
                isAdmin = user.isAdmin,
                isNew = user.isNew,
                tariff = tariff,
                isActive = isActive,
                isConfirmed = isConfirmed,
                subsEndDate = subsEndDate,
                createdAt = createdAt,
                usedTrafficBytes = usedTrafficBytes,
                trafficLimit = trafficLimit
            )
        )
        _currentUser.value = user
    }

    /**
     * Очистка текущей сессии (выход из системы)
     */
    suspend fun clearSession() {
        try {
            sessionDao.clearCurrentSession()
            _currentUser.value = null
        } catch (e: Exception) {
            // Логируем ошибку
            e.printStackTrace()
        }
    }

    suspend fun getAccessToken(): String? {
        val session = sessionDao.getCurrentSessionSync()

        return session?.token
    }

    suspend fun getCurrentUser(): User? {
        val session = sessionDao.getCurrentSessionSync()
        val now = System.currentTimeMillis()
        return session?.takeIf { it.tokenExpiry > now && !it.token.isNullOrBlank() }?.let {
            User(
                id = it.userId,
                email = it.email,
                subsEndDate = it.subsEndDate,
                isActive = it.isActive,
                createdAt = it.createdAt,
                tariff = it.tariff,
                emailConfirmed = it.isConfirmed,
                isAdmin = it.isAdmin,
                isNew = it.isNew,
                isLoggedIn = true,
                usedTrafficBytes = it.usedTrafficBytes,
                trafficLimit = it.trafficLimit
            )
        }
    }

    // ✅ ИСПРАВЛЕНО: безопасное получение ID
    suspend fun getUserId(): Long {
        return getCurrentUser()?.id ?: -1L
    }

    // ✅ Добавляем синхронный метод для получения ID (без корутин)
    fun getUserIdSync(): Long {
        return _currentUser.value?.id ?: -1L
    }

    // ✅ Добавляем синхронный метод для получения пользователя
    fun getCurrentUserSync(): User? {
        return _currentUser.value
    }

    // ✅ Добавляем синхронную проверку авторизации
    fun isLoggedInSync(): Boolean {
        return _currentUser.value != null
    }

    suspend fun replaceTokenIfCurrent(previousToken: String, newToken: String, expiry: Long): Boolean =
        sessionDao.replaceCurrentToken(previousToken, newToken, expiry) > 0

    suspend fun updateToken(newToken: String, expiry: Long) {
        sessionDao.updateCurrentToken(newToken, expiry)
    }

    suspend fun isLoggedIn(): Boolean {
        val session = sessionDao.getCurrentSessionSync()
        val now = System.currentTimeMillis()
        return session != null && session.tokenExpiry > now && !session.token.isNullOrBlank()
    }

    suspend fun logout() {
        sessionDao.clearCurrentSession()
        _currentUser.value = null
    }

    suspend fun logoutAllDevices() {
        val user = getCurrentUser()
        user?.let {
            sessionDao.deactivateAllUserSessions(it.id)
            sessionDao.clearCurrentSession()
            _currentUser.value = null
        }
    }

//    fun getUserSessions(userId: Long): Flow<List<Session>> {
//        return sessionDao.getUserSessions(userId)
//    }

    fun hasActiveSubscription(): Boolean {
        val user = _currentUser.value ?: return false
        return user.hasActiveSubscription()
    }

    fun updateCurrentUser(user: User?) {
        _currentUser.value = user
    }
}

