package ru.acestar.vpn.data.model

import kotlinx.serialization.Serializable
import java.sql.Date

@Serializable
data class RegRequest(
    val email: String,
    val password: String,
    val createDate: Date
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
data class AuthData(
    val id: Long,
    val email: String,
    val isConfirmed: Boolean,
    val isNew: Boolean = true,
    val token: String? = null,
    val tariff: String = "Free",
    val tokenExpiresAt: Date? = null,
    val subsEndDate: Long,
    val isAdmin: Boolean = false,
    val isActive: Boolean = true,
    val createdAt: Long = -1L
)