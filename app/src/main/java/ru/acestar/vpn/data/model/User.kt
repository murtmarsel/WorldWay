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
    val username: String? = null,
    /** Суммарный использованный трафик за текущий период тарифа (байты) */
    val usedTrafficBytes: Long = 0L
) {
    fun hasActiveSubscription(): Boolean {
        val tariffObj = Tariff.fromId(tariff)
        // Безлимитный тариф — всегда активен
        if (tariff == "Unlimited") return true
        // Тариф Hour — активен пока не исчерпан трафик
        if (tariff == "Hour") return usedTrafficBytes < (tariffObj.trafficLimitBytes ?: Long.MAX_VALUE)
        // Остальные — проверяем дату окончания
        return subsEndDate?.let { it > System.currentTimeMillis() } ?: false
    }

    /** Подписка заканчивается в течение [warnWithinMs] мс. */
    fun subscriptionExpiresWithin(warnWithinMs: Long): Boolean {
        if (tariff == "Unlimited") return false
        return subsEndDate?.let { end ->
            val remaining = end - System.currentTimeMillis()
            remaining in 0..warnWithinMs
        } ?: true
    }

    /** Процент использованного трафика (0..1). null если безлимит. */
    fun trafficUsageRatio(): Float? {
        val limit = Tariff.fromId(tariff).trafficLimitBytes ?: return null
        if (limit <= 0) return null
        return (usedTrafficBytes.toFloat() / limit).coerceIn(0f, 1f)
    }

    /** Остаток трафика в байтах. null если безлимит. */
    fun remainingTrafficBytes(): Long? {
        val limit = Tariff.fromId(tariff).trafficLimitBytes ?: return null
        return (limit - usedTrafficBytes).coerceAtLeast(0L)
    }

    /** Трафик исчерпан (для тарифов с лимитом). */
    fun isTrafficExhausted(): Boolean {
        val limit = Tariff.fromId(tariff).trafficLimitBytes ?: return false
        return usedTrafficBytes >= limit
    }
}
