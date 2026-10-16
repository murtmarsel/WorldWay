// data/database/SessionDao.kt
package ru.acestar.vpn.data.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import ru.acestar.vpn.data.model.CurrentSession
import ru.acestar.vpn.data.model.Session

@Dao
interface SessionDao {

    // Сессии
    @Query("SELECT * FROM sessions WHERE userId = :userId ORDER BY createdAt DESC")
    fun getUserSessions(userId: Long): Flow<List<Session>>

    @Query("SELECT * FROM sessions WHERE token = :token AND isActive = 1")
    suspend fun findSessionByToken(token: String): Session?

    @Insert
    suspend fun insertSession(session: Session): Long

    @Update
    suspend fun updateSession(session: Session)
//    @Update
//    suspend fun updateCurrentSession(session: CurrentSession)

    @Query("""UPDATE current_session
                        SET userId = :userId, email = :email, tariff = :tariff, subsEndDate = :subsEndDate,
                            isConfirmed = :isConfirmed, isActive = :isActive,
                            isAdmin = :isAdmin, isNew = :isNew,
                            usedTrafficBytes = :usedTrafficBytes, trafficLimit = :trafficLimit
                        WHERE id = 1 AND userId = :userId """)
    suspend fun updateCurrentSession(userId: Long, email: String, tariff: String, subsEndDate: Long,
                                     isConfirmed: Boolean, isActive: Boolean,
                                     isAdmin: Boolean, isNew: Boolean,
                                     usedTrafficBytes: Long, trafficLimit: Long)

//    @PrimaryKey
//    val id: Int = 1,
//    val userId: Long,
//    val email: String,
//    val tariff: String,
//    val subsEndDate: Long,
//    val token: String?,
//    val tokenExpiry: Long,
//    val isConfirmed: Boolean,
//    val isActive: Boolean,
//    val isAdmin: Boolean = false,
//    val isNew: Boolean = true,
//    val createdAt: Long


    @Query("UPDATE sessions SET isActive = 0 WHERE userId = :userId")
    suspend fun deactivateAllUserSessions(userId: Long)

    @Query("UPDATE sessions SET lastUsedAt = :timestamp WHERE id = :sessionId")
    suspend fun updateSessionLastUsed(sessionId: Long, timestamp: Long)

    @Query("DELETE FROM sessions WHERE tokenExpiry < :currentTime")
    suspend fun deleteExpiredSessions(currentTime: Long)

    // Текущая сессия
    @Query("SELECT * FROM current_session LIMIT 1")
    fun getCurrentSession(): Flow<CurrentSession?>

    @Query("SELECT * FROM current_session LIMIT 1")
    suspend fun getCurrentSessionSync(): CurrentSession?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCurrentSession(session: CurrentSession)

    @Query("DELETE FROM current_session")
    suspend fun clearCurrentSession()

    @Query("UPDATE current_session SET token = :token, tokenExpiry = :expiry WHERE id = 1 AND token = :previousToken")
    suspend fun replaceCurrentToken(previousToken: String, token: String, expiry: Long): Int

    @Query("UPDATE current_session SET token = :token, tokenExpiry = :expiry WHERE id = 1")
    suspend fun updateCurrentToken(token: String, expiry: Long)
}

