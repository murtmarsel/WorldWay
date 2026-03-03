package ru.acestar.vpn.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vpn_configs")
data class VpnConfigEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val serverHost: String,
    val configContent: String,
    val source: String,
    val createdAt: Long = System.currentTimeMillis()
)
