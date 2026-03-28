// api/AdminApiService.kt
package ru.acestar.vpn.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import ru.acestar.vpn.data.model.*
import ru.acestar.vpn.data.model.admin.UpdateUserRequest
import ru.acestar.vpn.data.model.admin.UserAdminData
import ru.acestar.vpn.data.model.admin.UserConfigFile
import ru.acestar.vpn.data.model.admin.UserLog
import ru.acestar.vpn.data.model.admin.UserPayment
import ru.acestar.vpn.data.model.admin.UserStats
import ru.acestar.vpn.data.repository.SessionManager
import ru.acestar.vpn.utils.CryptoHelper
import ru.acestar.vpn.utils.PasswordHasher
import java.util.concurrent.TimeUnit

class AdminApiService(private val sessionManager: SessionManager) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val baseUrl = "http://94.249.239.35:5001" // Замените на ваш URL

//    private var adminToken: String? = getAuthHeader()

//    fun setAdminToken(token: String?) {
//        adminToken = token
//    }

    private suspend fun getAuthHeader(): String? {
        val token = sessionManager.getAccessToken()
        return if (!token.isNullOrEmpty()) "Bearer $token" else null
    }
    private suspend fun getToken(): String? {
        val token = sessionManager.getAccessToken()
        return token
    }
    /**
     * Для методов админки с паролем: сначала JWT пользователя из сессии, иначе токен после [adminLogin].
     */
    private suspend fun createRequestBuilder(url: String): Request.Builder {
        val builder = Request.Builder().url(url)

        getAuthHeader()?.let { authHeader ->
            builder.addHeader("Authorization", authHeader)
            Log.d("AdminApiService", "🔑 Added auth header: ${authHeader.take(24)}...")
        } ?: Log.d("AdminApiService", "⚠️ No token available")

        builder.addHeader("Content-Type", "application/json")
        builder.addHeader("Accept", "application/json")

        return builder
    }

//    private fun bearerForAdminPanel(): String? {
//        val user = sessionManager.getAccessToken()
//        if (user.isNotEmpty()) return "Bearer $user"
//        val admin = adminToken
//        if (!admin.isNullOrEmpty()) return "Bearer $admin"
//        return null
//    }

    /** Только JWT пользователя (из [SessionManager]); нужен для запросов, где расшифровка идёт тем же токеном. */
//    private suspend fun requireUserAccessToken(): String? {
//        val t = getToken()
//        return t.takeIf { it.isNotEmpty() }
//    }

//    private fun createAuthRequestBuilder(url: String): Request.Builder {
//        val builder = Request.Builder().url(url)
//        token?.let {
//            builder.addHeader("Authorization", "Bearer $it")
//        }
//        return builder
//    }

    suspend fun adminLogin(password: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val passwordHashed = PasswordHasher.sha256Hex(password)
            val jsonBody = JSONObject().apply {
                put("password", passwordHashed)
            }.toString()

            val request = Request.Builder()
                .url("$baseUrl/api/admin/login")
                .post(jsonBody.toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()

                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Login failed: ${response.message}"))
                }

                val jsonResponse = JSONObject(responseBody ?: "")
                val success = jsonResponse.optBoolean("success", false)

                if (success) {
                    val token = jsonResponse.optString("token", "")
//                    adminToken = token
                    Result.success(token)
                } else {
                    val error = jsonResponse.optString("error", "Unknown error")
                    Result.failure(Exception(error))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getUsers(): Result<List<UserAdminData>> = withContext(Dispatchers.IO) {
        try {
            val request = createRequestBuilder("$baseUrl/api/admin/users")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()

                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.message}"))
                }

                val jsonResponse = JSONObject(responseBody ?: "")
                val success = jsonResponse.optBoolean("success", false)

                if (!success) {
                    val error = jsonResponse.optString("error", "Unknown error")
                    return@withContext Result.failure(Exception(error))
                }

                val usersArray = jsonResponse.optJSONArray("data") ?: JSONArray()
                val users = mutableListOf<UserAdminData>()

                for (i in 0 until usersArray.length()) {
                    val userJson = usersArray.getJSONObject(i)
                    users.add(
                        UserAdminData(
                            id = userJson.optInt("id"),
                            email = userJson.optString("email"),
                            tariff = userJson.optString("tariff"),
                            is_new = userJson.optBoolean("is_new"),
                            email_confirmed = userJson.optBoolean("email_confirmed"),
                            create_date = userJson.optString("create_date"),
                            subscription_expiry = userJson.optString("subscription_expiry").takeIf { it.isNotEmpty() },
                            is_active = userJson.optBoolean("is_active", true)
                        )
                    )
                }

                Result.success(users)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getClientFilesList(clientName: Int): Result<List<UserConfigFile>> =
        withContext(Dispatchers.IO) {
            try {
                val token = getToken()
                    ?: return@withContext Result.failure(
                        Exception("Нет токена пользователя. Войдите в приложение (JWT в сессии).")
                    )

                Log.d("AdminApiService", "📤 Getting configs list for user id: $clientName")

                val request = Request.Builder()
                    .url("$baseUrl/api/admin/users/$clientName/configs")
                    .get()
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .build()

                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    Log.d("AdminApiService", "📥 Response code: ${response.code}")

                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            Exception("HTTP ${response.code}: ${response.message}")
                        )
                    }

                    if (responseBody.isNullOrEmpty()) {
                        return@withContext Result.failure(Exception("Empty response"))
                    }

                    // Парсим JSON ответ
                    val jsonResponse = JSONObject(responseBody)
                    val success = jsonResponse.optBoolean("success", false)

                    if (!success) {
                        val error = jsonResponse.optString("error", "Unknown error")
                        return@withContext Result.failure(Exception(error))
                    }

                    val encrypted = jsonResponse.optBoolean("enc", false)
                    val encryptedData = jsonResponse.optString("data", null)

                    if (encrypted && encryptedData != null) {
                        // ✅ 1. Расшифровываем данные
                        val decryptedJson = CryptoHelper.decryptData(encryptedData, token)
                        Log.d("AdminApiService", "🔓 Decrypted data: $decryptedJson")

                        // ✅ 2. Парсим расшифрованный JSON
                        val filesData = JSONObject(decryptedJson)

                        // ✅ 3. Получаем массив files из data
                        val filesArray = filesData.optJSONArray("files") ?: JSONArray()
                        val filesList = mutableListOf<UserConfigFile>()

                        for (i in 0 until filesArray.length()) {
                            val fileJson = filesArray.getJSONObject(i)
                            val file = UserConfigFile(
                                name = fileJson.optString("name", ""),
                                path = fileJson.optString("path", ""),
                                size = fileJson.optLong("size", 0),
                                modified = fileJson.optDouble("modified", 0.0),
                                type = fileJson.optString("type", "unknown"),
                                category = fileJson.optString("category", null),
                                protocol = fileJson.optString("protocol", null),
                                config = fileJson.optString("config", null),
                                content = fileJson.optString("content", null)
                            )
                            filesList.add(file)
                        }

                        Result.success(filesList)
                    } else {
                        Result.failure(Exception("Expected encrypted data"))
                    }
                }
            } catch (e: Exception) {
                Log.e("AdminApiService", "Error getting files list", e)
                Result.failure(e)
            }
        }

    suspend fun updateUser(userId: Int, request: UpdateUserRequest): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val jsonBody = JSONObject().apply {
                request.email?.let { put("email", it) }
                request.tariff?.let { put("tariff", it) }
                request.email_confirmed?.let { put("email_confirmed", it) }
                request.is_active?.let { put("is_active", it) }
            }.toString()

            val requestBuilder = createRequestBuilder("$baseUrl/api/admin/users/$userId")
                .put(jsonBody.toRequestBody(jsonMediaType))

            client.newCall(requestBuilder.build()).execute().use { response ->
                val responseBody = response.body?.string()
                val jsonResponse = JSONObject(responseBody ?: "")
                val success = jsonResponse.optBoolean("success", false)

                if (success) {
                    Result.success(true)
                } else {
                    val error = jsonResponse.optString("error", "Update failed")
                    Result.failure(Exception(error))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun resetPassword(userId: Int, newPassword: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val jsonBody = JSONObject().apply {
                put("new_password", newPassword)
            }.toString()

            val request = createRequestBuilder("$baseUrl/api/admin/users/$userId/reset-password")
                .post(jsonBody.toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val jsonResponse = JSONObject(response.body?.string() ?: "")
                val success = jsonResponse.optBoolean("success", false)

                if (success) {
                    Result.success(true)
                } else {
                    val error = jsonResponse.optString("error", "Reset failed")
                    Result.failure(Exception(error))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteUser(userId: Int): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val request = createRequestBuilder("$baseUrl/api/admin/users/$userId")
                .delete()
                .build()

            client.newCall(request).execute().use { response ->
                val jsonResponse = JSONObject(response.body?.string() ?: "")
                val success = jsonResponse.optBoolean("success", false)

                if (success) {
                    Result.success(true)
                } else {
                    val error = jsonResponse.optString("error", "Delete failed")
                    Result.failure(Exception(error))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getUserStats(userId: Int): Result<UserStats> = withContext(Dispatchers.IO) {
        try {
            val request = createRequestBuilder("$baseUrl/api/admin/users/$userId/stats")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                val jsonResponse = JSONObject(responseBody ?: "")
                val success = jsonResponse.optBoolean("success", false)

                if (!success) {
                    val error = jsonResponse.optString("error", "Unknown error")
                    return@withContext Result.failure(Exception(error))
                }

                val data = jsonResponse.optJSONObject("data") ?: JSONObject()
                Result.success(
                    UserStats(
                        user_id = data.optInt("user_id"),
                        total_traffic = data.optLong("total_traffic"),
                        used_traffic = data.optLong("used_traffic"),
                        last_active = data.optString("last_active").takeIf { it.isNotEmpty() },
                        total_connections = data.optInt("total_connections"),
                        active_connections = data.optInt("active_connections")
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getUserPayments(userId: Int): Result<List<UserPayment>> = withContext(Dispatchers.IO) {
        try {
            val request = createRequestBuilder("$baseUrl/api/admin/users/$userId/payments")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                val jsonResponse = JSONObject(responseBody ?: "")
                val success = jsonResponse.optBoolean("success", false)

                if (!success) {
                    val error = jsonResponse.optString("error", "Unknown error")
                    return@withContext Result.failure(Exception(error))
                }

                val paymentsArray = jsonResponse.optJSONArray("data") ?: JSONArray()
                val payments = mutableListOf<UserPayment>()

                for (i in 0 until paymentsArray.length()) {
                    val paymentJson = paymentsArray.getJSONObject(i)
                    payments.add(
                        UserPayment(
                            id = paymentJson.optInt("id"),
                            amount = paymentJson.optString("amount"),
                            currency = paymentJson.optString("currency"),
                            status = paymentJson.optString("status"),
                            created_at = paymentJson.optString("created_at"),
                            description = paymentJson.optString("description").takeIf { it.isNotEmpty() }
                        )
                    )
                }

                Result.success(payments)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateSubscription(userId: Int, action: String, tariff: String? = null): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val jsonBody = JSONObject().apply {
                put("action", action)
                tariff?.let { put("tariff", it) }
            }.toString()

            val request = createRequestBuilder("$baseUrl/api/admin/users/$userId/subscription")
                .post(jsonBody.toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val jsonResponse = JSONObject(response.body?.string() ?: "")
                val success = jsonResponse.optBoolean("success", false)

                if (success) {
                    Result.success(true)
                } else {
                    val error = jsonResponse.optString("error", "Update failed")
                    Result.failure(Exception(error))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getUserLogs(userId: Int): Result<List<UserLog>> = withContext(Dispatchers.IO) {
        try {
            val request = createRequestBuilder("$baseUrl/api/admin/users/$userId/logs")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                val jsonResponse = JSONObject(responseBody ?: "")
                val success = jsonResponse.optBoolean("success", false)

                if (!success) {
                    val error = jsonResponse.optString("error", "Unknown error")
                    return@withContext Result.failure(Exception(error))
                }

                val logsArray = jsonResponse.optJSONArray("data") ?: JSONArray()
                val logs = mutableListOf<UserLog>()

                for (i in 0 until logsArray.length()) {
                    val logJson = logsArray.getJSONObject(i)
                    logs.add(
                        UserLog(
                            id = logJson.optInt("id"),
                            action = logJson.optString("action"),
                            details = logJson.optString("details"),
                            created_at = logJson.optString("created_at"),
                            ip_address = logJson.optString("ip_address").takeIf { it.isNotEmpty() }
                        )
                    )
                }

                Result.success(logs)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}