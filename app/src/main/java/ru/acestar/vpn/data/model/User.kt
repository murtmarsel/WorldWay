package ru.acestar.vpn.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class User(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val email: String,
    val password: String,
    val username: String,
    val createdAt: Long = System.currentTimeMillis(),
    val subscriptionEndDate: Long = 0,
    val isActive: Boolean = true
) {
    fun hasActiveSubscription(): Boolean {
        return subscriptionEndDate > System.currentTimeMillis()
    }
}
