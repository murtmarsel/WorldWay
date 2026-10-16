package ru.acestar.vpn.data.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import ru.acestar.vpn.data.model.User

@Dao
interface UserDao {
    @Query("UPDATE vpn_configs SET userId = :newId WHERE userId = :oldId")
    suspend fun moveConfigs(oldId: Long, newId: Long)

    @Query("UPDATE vpn_server_configs SET userId = :newId WHERE userId = :oldId")
    suspend fun moveServerConfigs(oldId: Long, newId: Long)

    @Query("UPDATE connection_stats SET userId = :newId WHERE userId = :oldId")
    suspend fun moveStats(oldId: Long, newId: Long)

    @Query("UPDATE sessions SET userId = :newId WHERE userId = :oldId")
    suspend fun moveSessions(oldId: Long, newId: Long)

    @Query("DELETE FROM users WHERE id = :oldId")
    suspend fun deleteById(oldId: Long)

    @Transaction
    suspend fun saveServerUser(user: User, oldId: Long?) {
        val target = getUserById(user.id)
        check(target == null || target.email.equals(user.email, ignoreCase = true)) {
            "Конфликт локального ID пользователя; требуется миграция учётных записей"
        }
        if (oldId != null && oldId != user.id) {
            moveConfigs(oldId, user.id)
            moveServerConfigs(oldId, user.id)
            moveStats(oldId, user.id)
            moveSessions(oldId, user.id)
            deleteById(oldId)
        }
        insert(user)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(user: User): Long


//    @PrimaryKey(autoGenerate = true)
//    val id: Long = 0,
//    val email: String,
//    val password: String? = null,
//    val createdAt: Long = System.currentTimeMillis(),
//    val tariff: String = "Trial",
//    val subsEndDate: Long? = 0,
//    val isActive: Boolean = true,
//    val emailConfirmed: Boolean = false,
//    val isNew: Boolean = true,
//    val isAdmin: Boolean = false,
//    val isLoggedIn: Boolean = false,
//    val username: String? = null
    @Query("""
        UPDATE users
        SET  email = :email, emailConfirmed = :isConfirmed, tariff = :tariff, 
            isNew = :isNew, isActive = :isActive, subsEndDate = :subsEndDate,
            usedTrafficBytes = :usedTrafficBytes, trafficLimit = :trafficLimit
        WHERE id = :id""")
    suspend fun updateUser(id: Long, email: String?, isConfirmed: Boolean,
                           tariff: String, isNew: Boolean, isActive: Boolean,
                           subsEndDate: Long?, usedTrafficBytes: Long, trafficLimit: Long)

    @Query("UPDATE users SET usedTrafficBytes = usedTrafficBytes + :bytes WHERE id = :userId")
    suspend fun addUsedTraffic(userId: Long, bytes: Long)

    @Query("UPDATE users SET usedTrafficBytes = 0 WHERE id = :userId")
    suspend fun resetUsedTraffic(userId: Long)

    @Query("SELECT usedTrafficBytes FROM users WHERE id = :userId")
    suspend fun getUsedTraffic(userId: Long): Long?
    @Update
    suspend fun update(user: User)

    @Delete
    suspend fun delete(user: User)

    @Query("SELECT * FROM users WHERE email = :email LIMIT 1")
    suspend fun getUserByEmail(email: String): User?

    @Query("SELECT * FROM users WHERE email = :email AND password = :password LIMIT 1")
    suspend fun login(email: String, password: String): User?

    @Query("SELECT * FROM users WHERE id = :id LIMIT 1")
    suspend fun getUserById(id: Long): User?

    @Query("SELECT * FROM users WHERE id = :id")
    fun observeUser(id: Long): Flow<User?>

    @Query("UPDATE users SET subsEndDate = :endDate WHERE id = :userId")
    suspend fun updateSubscription(userId: Long, endDate: Long?)

    @Query("UPDATE users SET isNew = :value WHERE id = :userId")
    suspend fun setIsNewUser(userId: Long, value: Boolean)
}


