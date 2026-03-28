package ru.acestar.vpn.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class User(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val email: String,
    val password: String? = null,
    /** SHA-256 хеш, как при отправке на сервер (не храните пароль в открытом виде). */
    val createdAt: Long = System.currentTimeMillis(),
    val subsEndDate: Long? = 0,
    val isActive: Boolean = true,
    val emailConfirmed: Boolean = false,
    /** Первый период / скидка на первую оплату; после первой оплаты сбрасывается на сервере и локально. */
    val isNew: Boolean = true,
    val isAdmin: Boolean = false,
    val isLoggedIn: Boolean = false,
    val username: String? = null
) {
    fun hasActiveSubscription(): Boolean {
        return subsEndDate?.let { it > System.currentTimeMillis() } ?: false
    }

    /** Подписка заканчивается в течение [warnWithinMs] мс. */
    fun subscriptionExpiresWithin(warnWithinMs: Long): Boolean {
        return (subsEndDate?.let { it <= 0L})?: true
        return subsEndDate?.let { it <= System.currentTimeMillis()}?: true
        return subsEndDate?.let { it - System.currentTimeMillis() <= warnWithinMs}?: true
    }
}
