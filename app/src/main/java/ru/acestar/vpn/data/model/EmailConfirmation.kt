// data/model/EmailConfirmation.kt
package ru.acestar.vpn.data.model

data class EmailConfirmationRequest(
    val email: String,
    val code: String? = null
)

data class EmailConfirmationResponse(
    val success: Boolean,
    val message: String,
    val email: String? = null,
    val isVerified: Boolean = false
)

sealed class EmailConfirmationState {
    object Idle : EmailConfirmationState()
    object Loading : EmailConfirmationState()
    data class CodeSent(val email: String) : EmailConfirmationState()
    data class Verified(val email: String) : EmailConfirmationState()
    data class Error(val message: String) : EmailConfirmationState()
}