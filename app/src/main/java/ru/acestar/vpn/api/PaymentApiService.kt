package ru.acestar.vpn.api

import android.util.Log
import ru.acestar.vpn.data.model.PaymentRequest
import ru.acestar.vpn.data.model.PaymentResponse
import ru.acestar.vpn.data.model.PaymentStatusResponse
import ru.acestar.vpn.data.repository.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import ru.acestar.vpn.data.model.Amount
import ru.acestar.vpn.data.model.PaymentData
import ru.acestar.vpn.data.model.PaymentStatusData
import java.io.IOException

class PaymentApiService(
    private val sessionManager: SessionManager? = null
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // Базовый URL вашего Flask API
    private val baseUrl = "http://94.249.239.35:5001"

    // Токен авторизации (получите его после логина)
    private var authToken: String? = null

    fun setAuthToken(token: String) {
        authToken = token
    }

    suspend fun createPayment(
        amountValue: String,
        returnUrl: String,
        description: String? = null,
        metadata: Map<String, String>? = null
    ): Result<PaymentData> = withContext(Dispatchers.IO) {
        try {
            val token = sessionManager?.getAccessToken() ?: authToken
            if (token.isNullOrBlank()) {
                return@withContext Result.failure(Exception("Нет токена. Войдите в приложение заново."))
            }

            // Создаем запрос
            val paymentRequest = PaymentRequest(
                amount = Amount(value = amountValue),
                return_url = returnUrl,
                description = description,
                metadata = metadata
            )

            // Конвертируем в JSON
            val jsonBody = JSONObject().apply {
                put("amount", JSONObject().apply {
                    put("value", paymentRequest.amount.value)
                    put("currency", paymentRequest.amount.currency)
                })
                put("return_url", paymentRequest.return_url)
                if (description != null) {
                    put("description", description)
                }
                if (metadata != null) {
                    put("metadata", JSONObject(metadata))
                }
            }

            // Создаем HTTP запрос
            val requestBuilder = Request.Builder()
                .url("$baseUrl/api/v1/payments")
                .post(jsonBody.toString().toRequestBody(jsonMediaType))
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer $token")

            val request = requestBuilder.build()

            // Выполняем запрос
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()

                if (!response.isSuccessful) {
                    val errorMsg = try {
                        val json = JSONObject(responseBody ?: "")
                        json.optString("error", "Unknown error")
                    } catch (e: Exception) {
                        "HTTP ${response.code}: ${response.message}"
                    }
                    return@withContext Result.failure(Exception(errorMsg))
                }

                // Парсим ответ
                val json = JSONObject(responseBody ?: "")
                val success = json.optBoolean("success", false)

                if (success) {
                    val data = json.getJSONObject("data")
                    val paymentData = PaymentData(
                        payment_id = data.getString("payment_id"),
                        status = data.getString("status"),
                        confirmation_url = data.getString("confirmation_url"),
                        paid = data.optBoolean("paid", false)
                    )
                    Result.success(paymentData)
                } else {
                    val error = json.optString("error", "Unknown error")
                    Result.failure(Exception(error))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getPaymentStatus(paymentId: String): Result<PaymentStatusData> = withContext(Dispatchers.IO) {
        try {
            val token = sessionManager?.getAccessToken() ?: authToken
            if (token.isNullOrBlank()) {
                return@withContext Result.failure(Exception("Нет токена. Войдите в приложение заново."))
            }

            val requestBuilder = Request.Builder()
                .url("$baseUrl/api/v1/payments/$paymentId")
                .get()
                .addHeader("Authorization", "Bearer $token")

            val request = requestBuilder.build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()

                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code}"))
                }

                val json = JSONObject(responseBody ?: "")
                val success = json.optBoolean("success", false)

                if (success) {
                    val data = json.getJSONObject("data")
                    val amountObj = data.getJSONObject("amount")

                    val statusData = PaymentStatusData(
                        id = data.getString("id"),
                        status = data.getString("status"),
                        paid = data.optBoolean("paid", false),
                        amount = Amount(
                            value = amountObj.getString("value"),
                            currency = amountObj.getString("currency")
                        ),
                        description = data.optString("description"),
                        metadata = if (data.has("metadata")) {
                            val metaObj = data.getJSONObject("metadata")
                            val map = mutableMapOf<String, String>()
                            metaObj.keys().forEach { key ->
                                map[key] = metaObj.getString(key)
                            }
                            map
                        } else null
                    )
                    Result.success(statusData)
                } else {
                    val error = json.optString("error", "Unknown error")
                    Result.failure(Exception(error))
                }
            }
        } catch (e: Exception) {
            Log.e("PaymentApiService", "Error getting payment status", e)
            Result.failure(e)
        }
    }

    /**
     * Получает историю платежей текущего пользователя.
     * GET /api/v1/payments/history
     */
    suspend fun getPaymentHistory(): Result<List<ru.acestar.vpn.data.model.admin.UserPayment>> =
        withContext(Dispatchers.IO) {
            try {
                val token = sessionManager?.getAccessToken()
                if (token.isNullOrBlank()) {
                    return@withContext Result.failure(Exception("Нет токена"))
                }
                val request = Request.Builder()
                    .url("$baseUrl/api/v1/payments/history")
                    .get()
                    .addHeader("Authorization", "Bearer $token")
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: "{}"
                    if (!response.isSuccessful)
                        return@withContext Result.failure(Exception("HTTP ${response.code}"))

                    val json = org.json.JSONObject(body)
                    if (!json.optBoolean("success", false))
                        return@withContext Result.failure(Exception(json.optString("error")))

                    val arr = json.optJSONArray("data") ?: org.json.JSONArray()
                    val list = (0 until arr.length()).map { i ->
                        val p = arr.getJSONObject(i)
                        ru.acestar.vpn.data.model.admin.UserPayment(
                            id          = p.optInt("id"),
                            amount      = p.optString("amount"),
                            currency    = p.optString("currency", "RUB"),
                            status      = p.optString("status"),
                            created_at  = p.optString("created_at"),
                            description = p.optString("description").takeIf { it.isNotEmpty() },
                            tariff      = p.optString("tariff").takeIf { it.isNotEmpty() },
                            period      = p.optString("period").takeIf { it.isNotEmpty() },
                            source      = p.optString("source").takeIf { it.isNotEmpty() }
                        )
                    }
                    Result.success(list)
                }
            } catch (e: Exception) {
                Log.e("PaymentApiService", "getPaymentHistory failed", e)
                Result.failure(e)
            }
        }
}