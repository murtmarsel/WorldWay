package ru.acestar.vpn.data.repository

import kotlinx.coroutines.flow.Flow
import ru.acestar.vpn.data.dao.ConnectionStatsDao
import ru.acestar.vpn.data.model.ConnectionStats

class StatsRepository(private val statsDao: ConnectionStatsDao) {
    
    suspend fun startConnection(userId: Long, serverName: String): Long {
        val stats = ConnectionStats(
            userId = userId,
            serverName = serverName,
            connectedAt = System.currentTimeMillis()
        )
        return statsDao.insert(stats)
    }
    
    suspend fun endConnection(statsId: Long, bytesReceived: Long = 0, bytesSent: Long = 0) {
        val stats = statsDao.getById(statsId) ?: return
        val updatedStats = stats.copy(
            disconnectedAt = System.currentTimeMillis(),
            bytesReceived = bytesReceived,
            bytesSent = bytesSent
        )
        statsDao.update(updatedStats)
    }
    
    fun getStatsByUser(userId: Long): Flow<List<ConnectionStats>> {
        return statsDao.getStatsByUser(userId)
    }
    
    suspend fun getRecentStats(userId: Long): List<ConnectionStats> {
        return statsDao.getRecentStats(userId)
    }
    
    suspend fun getTotalUsage(userId: Long): Pair<Long, Long> {
        val received = statsDao.getTotalBytesReceived(userId) ?: 0
        val sent = statsDao.getTotalBytesSent(userId) ?: 0
        return Pair(received, sent)
    }
    
    fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.2f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
        }
    }
}
