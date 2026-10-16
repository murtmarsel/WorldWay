package ru.acestar.vpn.data.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import ru.acestar.vpn.data.model.ConnectionStats

@Dao
interface ConnectionStatsDao {
    @Insert
    suspend fun insert(stats: ConnectionStats): Long

    @Update
    suspend fun update(stats: ConnectionStats)

    @Query("SELECT * FROM connection_stats WHERE userId = :userId ORDER BY connectedAt DESC")
    fun getStatsByUser(userId: Long): Flow<List<ConnectionStats>>

    @Query("SELECT * FROM connection_stats WHERE userId = :userId ORDER BY connectedAt DESC LIMIT 10")
    suspend fun getRecentStats(userId: Long): List<ConnectionStats>

    @Query("SELECT SUM(bytesReceived) FROM connection_stats WHERE userId = :userId")
    suspend fun getTotalBytesReceived(userId: Long): Long?

    @Query("SELECT SUM(bytesSent) FROM connection_stats WHERE userId = :userId")
    suspend fun getTotalBytesSent(userId: Long): Long?

    @Query("SELECT * FROM connection_stats WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ConnectionStats?
}
