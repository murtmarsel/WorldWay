package ru.acestar.vpn.data.model

import kotlinx.serialization.Serializable
import java.sql.Date

@Serializable
data class RegRequest(
    val email: String,
    val password: String,
    val isConfirmed: Boolean,
    val createDate: Date,
    val tariff: String,
    val isNew: Boolean,
    val isAdmin: Boolean = false
)

@Serializable
data class AuthRequest(
    val email: String,
    val password: String,
    val metadata: Map<String, String>? = null
)

@Serializable
data class GetConfigsRequest(
    val userId: Int,
)

@Serializable
data class ConfirmEmail(
    val email: String,
    val isConfirmed: Boolean,
)

@Serializable
data class SendConfirmEmailCode(
    val email: String,
    val isConfirmed: Boolean,
)

@Serializable
data class ConfirmData(
    val isConfirmed: Boolean,
)

@Serializable
data class AuthData(
    val email: String,
    val isConfirmed: Boolean,
    val isNew: Boolean = true,
    val token: String? = null,
    val tariff: String? = "Free",
    val tokenExpiresAt: Date? = null,
    val subsEndDate: Long?  = null,
    val isAdmin: Boolean = false
)