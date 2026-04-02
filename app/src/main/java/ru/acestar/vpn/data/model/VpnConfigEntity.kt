package ru.acestar.vpn.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vpn_configs")
data class VpnConfigEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val userId: Long?,
    val name: String,
    val serverHost: String,
    val configContent: String,
    val source: String,
    val isFavorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "vpn_server_configs")
data class VpnServerConfigEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val userId: Long,
    val name: String,
    val type: String,
    val category: String,
    val protocol: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis()
)
