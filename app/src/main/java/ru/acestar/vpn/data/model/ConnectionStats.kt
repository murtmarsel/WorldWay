package ru.acestar.vpn.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "connection_stats")
data class ConnectionStats(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val userId: Long,
    val serverName: String,
    val connectedAt: Long,
    val disconnectedAt: Long? = null,
    val bytesReceived: Long = 0,
    val bytesSent: Long = 0
) {
    fun getDurationSeconds(): Long {
        val endTime = disconnectedAt ?: System.currentTimeMillis()
        return (endTime - connectedAt) / 1000
    }
}
