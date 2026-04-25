package ru.acestar.vpn.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class User(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val email: String,
    val password: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val tariff: String = "Trial",
    val subsEndDate: Long? = 0,
    val isActive: Boolean = true,
    val emailConfirmed: Boolean = false,
    val isNew: Boolean = true,
    val isAdmin: Boolean = false,
    val isLoggedIn: Boolean = false,
    val username: String? = null
) {
    fun hasActiveSubscription(): Boolean {
        val subs = subsEndDate?.let { it > System.currentTimeMillis()} ?: false
        val tar = tariff == "Unlimited"
        return subs or tar
    }

    /** Подписка заканчивается в течение [warnWithinMs] мс. */
    fun subscriptionExpiresWithin(warnWithinMs: Long): Boolean {
        return (subsEndDate?.let { it <= 0L})?: true
        return subsEndDate?.let { it <= System.currentTimeMillis()}?: true
        return subsEndDate?.let { it - System.currentTimeMillis() <= warnWithinMs}?: true
    }
}
