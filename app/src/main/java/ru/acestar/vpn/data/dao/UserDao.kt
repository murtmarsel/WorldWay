package ru.acestar.vpn.data.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import ru.acestar.vpn.data.model.User

@Dao
interface UserDao {
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
            isNew = :isNew, isActive = :isActive, subsEndDate = :subsEndDate
        WHERE id = :id""")
    suspend fun updateUser(id: Long, email: String?, isConfirmed: Boolean,
                           tariff: String, isNew: Boolean, isActive: Boolean, subsEndDate: Long?)

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
