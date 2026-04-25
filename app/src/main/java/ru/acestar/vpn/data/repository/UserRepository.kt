package ru.acestar.vpn.data.repository

import kotlinx.coroutines.flow.Flow
import ru.acestar.vpn.data.dao.UserDao
import ru.acestar.vpn.data.model.AuthData
import ru.acestar.vpn.data.model.User
import java.sql.Date

class UserRepository(private val userDao: UserDao) {

    suspend fun register(email: String, password: String): Result<User> {
        val existingUser = userDao.getUserByEmail(email)
        if (existingUser != null) {
            return Result.failure(Exception("Пользователь с таким email уже существует"))
        }

        val trialEndDate = System.currentTimeMillis() + (7 * 24 * 60 * 60 * 1000L)

        val user = User(
            email = email,
            password = password,
            username = email.substringBefore("@").ifBlank { email },
            subsEndDate = trialEndDate
        )

        val id = userDao.insert(user)
        return Result.success(user.copy(id = id))
    }

    suspend fun login(email: String, password: String): Result<User> {
        val user = userDao.login(email, password)
        return if (user != null) {
            Result.success(user)
        } else {
            Result.failure(Exception("Неверный email или пароль"))
        }
    }

    suspend fun getUserById(id: Long): User? {
        return userDao.getUserById(id)
    }
    suspend fun updateUser(user: User){
        userDao.update(user)
    }

    /**
     * После успешной авторизации/регистрации на сервере синхронизируем пользователя в локальной БД.
     * @param passwordHash SHA-256 ([ru.acestar.vpn.utils.PasswordHasher.hashForServer]).
     * Если [AuthData.isNew] и сервер не прислал дату окончания подписки — даём 7 дней пробного периода.
     */
    suspend fun upsertAfterServerAuth(email: String, passwordHash: String, authData: AuthData): User {
        val username = email.substringBefore("@").ifBlank { email }
        val existing = userDao.getUserByEmail(email)
        val now = System.currentTimeMillis()
        val sevenDays = now + (7L * 24L * 60L * 60L * 1000L)
        val subscriptionEnd: Long? = authData.subsEndDate
        return if (existing != null) {
            val updated = existing.copy(
                password = passwordHash,
                username = username,
                tariff = authData.tariff,
                emailConfirmed = authData.isConfirmed,
                isNew = authData.isNew,
                isAdmin = authData.isAdmin,
                isActive = authData.isActive,
                subsEndDate = subscriptionEnd
            )
            userDao.update(updated)
            updated
        } else {
            val newUser = User(
                email = email,
                password = passwordHash,
                username = username,
                tariff = authData.tariff,
                subsEndDate = subscriptionEnd,
                emailConfirmed = authData.isConfirmed,
                isNew = authData.isNew,
                isAdmin = authData.isAdmin,
                isActive = authData.isActive
            )
            val id = userDao.insert(newUser)
            userDao.getUserById(id) ?: newUser.copy(id = id)
        }
    }

    suspend fun setIsNewUser(userId: Long, value: Boolean) {
        userDao.setIsNewUser(userId, value)
    }

    fun observeUser(id: Long): Flow<User?> {
        return userDao.observeUser(id)
    }


    suspend fun updateSubscription(userId: Long, durationDays: Int) {
        val currentTime = System.currentTimeMillis()
        val endDate = currentTime + (durationDays * 24 * 60 * 60 * 1000L)
        userDao.updateSubscription(userId, endDate)
    }

    suspend fun extendSubscription(userId: Long, additionalDays: Int) {
        val user = userDao.getUserById(userId) ?: return
        val baseTime = user.subsEndDate?.let {
            if (it > System.currentTimeMillis()) {
                user.subsEndDate
            } else {
                System.currentTimeMillis()
            }
        }
        val newEndDate: Long? = (baseTime?.plus(additionalDays * 24 * 60 * 60 * 1000L))
        userDao.updateSubscription(userId, newEndDate)
    }
}
