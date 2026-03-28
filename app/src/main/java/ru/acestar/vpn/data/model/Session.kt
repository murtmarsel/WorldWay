// data/model/Session.kt
package ru.acestar.vpn.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

@Entity(tableName = "sessions")
data class Session(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val userId: Long,
    val accessToken: String?,
    val refreshToken: String? = null,
    val tokenExpiry: Long,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis(),
    val email: String? = null,
    val ipAddress: String? = null
)

@Entity(tableName = "current_session")
data class CurrentSession(
    @PrimaryKey
    val id: Int = 1, // Всегда одна запись
    val sessionId: Long,
    val userId: Long,
    val userEmail: String,
    val accessToken: String?,
    val tokenExpiry: Long,
    val isAdmin: Boolean = false,
    val isNew: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis()
)