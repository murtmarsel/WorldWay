package ru.acestar.vpn.data.repository

import kotlinx.coroutines.flow.Flow
import ru.acestar.vpn.data.dao.UserDao
import ru.acestar.vpn.data.model.User

class UserRepository(private val userDao: UserDao) {
    
    suspend fun register(email: String, password: String, username: String): Result<User> {
        val existingUser = userDao.getUserByEmail(email)
        if (existingUser != null) {
            return Result.failure(Exception("Пользователь с таким email уже существует"))
        }
        
        val trialEndDate = System.currentTimeMillis() + (7 * 24 * 60 * 60 * 1000L)
        
        val user = User(
            email = email,
            password = password,
            username = username,
            subscriptionEndDate = trialEndDate
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
        val baseTime = if (user.subscriptionEndDate > System.currentTimeMillis()) {
            user.subscriptionEndDate
        } else {
            System.currentTimeMillis()
        }
        val newEndDate = baseTime + (additionalDays * 24 * 60 * 60 * 1000L)
        userDao.updateSubscription(userId, newEndDate)
    }
}
