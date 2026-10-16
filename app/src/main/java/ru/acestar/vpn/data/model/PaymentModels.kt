package ru.acestar.vpn.data.model

import kotlinx.serialization.Serializable

@Serializable
data class PaymentRequest(
    val amount: Amount,
    val return_url: String,
    val description: String? = null,
    val metadata: Map<String, String>? = null
)

@Serializable
data class Amount(
    val value: String,
    val currency: String = "RUB"
)

@Serializable
data class PaymentResponse(
    val success: Boolean,
    val data: PaymentData? = null,
    val error: String? = null
)

@Serializable
data class PaymentData(
    val payment_id: String,
    val status: String,
    val confirmation_url: String,
    val paid: Boolean
)

@Serializable
data class PaymentStatusResponse(
    val success: Boolean,
    val data: PaymentStatusData? = null,
    val error: String? = null
)

@Serializable
data class PaymentStatusData(
    val id: String,
    val status: String,
    val paid: Boolean,
    val amount: Amount,
    val description: String? = null,
    val metadata: Map<String, String>? = null
)