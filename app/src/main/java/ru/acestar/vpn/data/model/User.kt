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
    val usedTrafficBytes: Long = 0L,
    /**
     * Лимит трафика — берётся с сервера, может отличаться от дефолтного лимита тарифа.
     * 0 = использовать дефолт из [Tariff], -1 = безлимит (переопределён администратором).
     */
    val trafficLimit: Long = 0L
) {
    /**
     * Эффективный лимит трафика: если сервер прислал явный лимит — используем его,
     * иначе берём дефолт из тарифа.
     */
    fun effectiveTrafficLimit(): Long? {
        if (trafficLimit == -1L) return null          // явный безлимит от сервера
        if (trafficLimit > 0L) return trafficLimit    // явный лимит от сервера
        return Tariff.fromId(tariff).trafficLimitBytes // дефолт тарифа
    }

    fun hasActiveSubscription(): Boolean {
        if (tariff == "Unlimited") return true
        if (tariff == "Hour") {
            val timeOk = subsEndDate?.let { it > System.currentTimeMillis() } ?: false
            val limit = effectiveTrafficLimit()
            val trafficOk = limit == null || usedTrafficBytes < limit
            return timeOk && trafficOk
        }
        return subsEndDate?.let { it > System.currentTimeMillis() } ?: false
    }

    fun subscriptionExpiresWithin(warnWithinMs: Long): Boolean {
        if (tariff == "Unlimited") return false
        return subsEndDate?.let { end ->
            val remaining = end - System.currentTimeMillis()
            remaining in 0..warnWithinMs
        } ?: true
    }

    fun trafficUsageRatio(): Float? {
        val limit = effectiveTrafficLimit() ?: return null
        if (limit <= 0) return null
        return (usedTrafficBytes.toFloat() / limit).coerceIn(0f, 1f)
    }

    fun remainingTrafficBytes(): Long? {
        val limit = effectiveTrafficLimit() ?: return null
        return (limit - usedTrafficBytes).coerceAtLeast(0L)
    }

    fun isTrafficExhausted(): Boolean {
        val limit = effectiveTrafficLimit() ?: return false
        return usedTrafficBytes >= limit
    }
}
