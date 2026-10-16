package ru.acestar.vpn.data.model.admin

import kotlinx.serialization.Serializable
import java.util.Date

data class AdminUserDto(
    val id: Long,
    val email: String,
    val isEmailConfirmed: Boolean,
    val isNew: Boolean,
    val isActive: Boolean,
    val subscriptionEndMs: Long,
    val isAdmin: Boolean
)

data class AdminConfigFileDto(
    val name: String,
    val path: String,
    val size: Long,
    val preview: String
)

data class AdminUsageLogDto(
    val id: String,
    val userId: Long,
    val message: String,
    val createdAtMs: Long
)

@Serializable
data class AdminLoginRequest(val password: String)

@Serializable
data class AdminLoginResponse(
    val success: Boolean,
    val token: String? = null,
    val error: String? = null
)

@Serializable
data class UserListResponse(
    val success: Boolean,
    val data: List<UserAdminData>? = null,
    val error: String? = null
)

@Serializable
data class UserAdminData(
    val id: Int,
    val email: String,
    val tariff: String,
    val is_new: Boolean,
    val email_confirmed: Boolean,
    val create_date: String,
    val subscription_expiry: String? = null,
    val is_active: Boolean = true,
    val used_traffic: Long = 0L,
    val traffic_limit: Long = 0L
)

data class UserConfigFile(
    val name: String,
    val path: String,
    val size: Long,
    val modified: Double,
    val type: String,
    val category: String? = null,
    val protocol: String? = null,
    val config: String? = null,
    val content: String,
    /**
     * Группа авто-режима: "auto1" | "auto2" | null.
     * Сервер возвращает это поле в списке конфигов.
     * null или отсутствие = "auto1" (основной авто-режим).
     */
    val group: String? = null
) {
    val modifiedDate: Date get() = Date((modified * 1000).toLong())
    val sizeFormatted: String get() {
        val kb = size / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1 -> String.format("%.2f MB", mb)
            kb >= 1 -> String.format("%.2f KB", kb)
            else -> "$size B"
        }
    }
}

@Serializable
data class UserConfigsResponse(
    val success: Boolean,
    val data: List<UserConfigFile>? = null,
    val error: String? = null
)

@Serializable
data class UpdateUserRequest(
    val email: String? = null,
    val tariff: String? = null,
    val email_confirmed: Boolean? = null,
    val is_active: Boolean? = null,
    val used_traffic: Long? = null,
    val traffic_limit: Long? = null
)

@Serializable
data class ResetPasswordRequest(val new_password: String)

@Serializable
data class UserStatsResponse(
    val success: Boolean,
    val data: UserStats? = null,
    val error: String? = null
)

@Serializable
data class UserStats(
    val user_id: Int,
    val total_traffic: Long,
    val used_traffic: Long,
    val last_active: String? = null,
    val total_connections: Int,
    val active_connections: Int,
    val last_login: String? = null,
    val login_count: Int = 0,
    val last_ip: String? = null
)

@Serializable
data class UserPaymentsResponse(
    val success: Boolean,
    val data: List<UserPayment>? = null,
    val error: String? = null
)

@Serializable
data class UserPayment(
    val id: Int,
    val amount: String,
    val currency: String,
    val status: String,
    val created_at: String,
    val description: String? = null,
    val tariff: String? = null,
    val period: String? = null,
    val source: String? = null   // "payment" | "ad"
)

@Serializable
data class UserLogsResponse(
    val success: Boolean,
    val data: List<UserLog>? = null,
    val error: String? = null
)

@Serializable
data class UserLog(
    val id: Int,
    val action: String,
    val details: String,
    val created_at: String,
    val ip_address: String? = null
)

@Serializable
data class UpdateSubscriptionRequest(
    val action: String,   // pause | resume | cancel
    val tariff: String? = null
)

/** Запрос на изменение трафика пользователя */
@Serializable
data class UpdateTrafficRequest(
    val delta_bytes: Long,   // положительное — добавить, отрицательное — убавить
    val reset: Boolean = false
)

/** Запрос на сброс пароля через email */
@Serializable
data class SendPasswordResetEmailRequest(
    val email: String
)
