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
import ru.acestar.vpn.data.model.admin.*
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
    private val baseUrl = "http://94.249.239.35:5001"

    private suspend fun getToken(): String? = sessionManager.getAccessToken()

    private suspend fun createRequestBuilder(url: String): Request.Builder {
        val builder = Request.Builder().url(url)
        getToken()?.let { builder.addHeader("Authorization", "Bearer $it") }
        builder.addHeader("Content-Type", "application/json")
        builder.addHeader("Accept", "application/json")
        return builder
    }

    /** Шифрует payload через CryptoHelper и оборачивает в {"data": "<encrypted>"} */
    private suspend fun encryptedBody(payload: JSONObject): String {
        val token = getToken() ?: throw Exception("Нет токена авторизации")
        val encrypted = CryptoHelper.encryptData(payload.toString(), token)
        return JSONObject().apply { put("data", encrypted) }.toString()
    }

    /** Расшифровывает ответ сервера если enc=true */
    private suspend fun decryptResponse(jsonResponse: JSONObject): JSONObject {
        val encrypted = jsonResponse.optBoolean("enc", false)
        val rawData = jsonResponse.optString("data", null)
            ?: return JSONObject()
        return if (encrypted) {
            val token = getToken() ?: throw Exception("Нет токена для расшифровки")
            JSONObject(CryptoHelper.decryptData(rawData, token))
        } else {
            JSONObject(rawData)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AUTH
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun adminLogin(password: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val token = getToken()
            val passwordHashed = PasswordHasher.sha256Hex(password)
            val jsonBody = JSONObject().apply { put("password", passwordHashed) }.toString()

            val requestBuilder = Request.Builder()
                .url("$baseUrl/api/admin/login")
                .post(jsonBody.toRequestBody(jsonMediaType))
                .addHeader("Content-Type", "application/json")

            // Передаём JWT пользователя — сервер проверяет что запрос от авторизованного аккаунта
            if (!token.isNullOrBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $token")
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                val json = JSONObject(response.body?.string() ?: "{}")
                if (!response.isSuccessful)
                    return@withContext Result.failure(Exception(json.optString("error", "HTTP ${response.code}")))
                if (json.optBoolean("success", false))
                    Result.success(json.optString("token", ""))
                else
                    Result.failure(Exception(json.optString("error", "Login failed")))
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // USERS
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun getUsers(): Result<List<UserAdminData>> = withContext(Dispatchers.IO) {
        try {
            val request = createRequestBuilder("$baseUrl/api/admin/users").get().build()
            client.newCall(request).execute().use { response ->
                val json = JSONObject(response.body?.string() ?: "{}")
                if (!response.isSuccessful)
                    return@withContext Result.failure(Exception("HTTP ${response.code}"))
                if (!json.optBoolean("success", false))
                    return@withContext Result.failure(Exception(json.optString("error")))

                val arr = json.optJSONArray("data") ?: JSONArray()
                val users = (0 until arr.length()).map { i ->
                    val u = arr.getJSONObject(i)
                    UserAdminData(
                        id = u.optInt("id"),
                        email = u.optString("email"),
                        tariff = u.optString("tariff"),
                        is_new = u.optBoolean("is_new"),
                        email_confirmed = u.optBoolean("email_confirmed"),
                        create_date = u.optString("create_date"),
                        subscription_expiry = u.optString("subscription_expiry").takeIf { it.isNotEmpty() },
                        is_active = u.optBoolean("is_active", true),
                        used_traffic = u.optLong("used_traffic", 0L),
                        traffic_limit = u.optLong("traffic_limit", 0L)
                    )
                }
                Result.success(users)
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONFIGS
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun getClientFilesList(userId: Int): Result<List<UserConfigFile>> =
        withContext(Dispatchers.IO) {
            try {
                val token = getToken()
                    ?: return@withContext Result.failure(Exception("Нет токена"))

                val request = Request.Builder()
                    .url("$baseUrl/api/admin/users/$userId/configs")
                    .get()
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Content-Type", "application/json")
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    if (!response.isSuccessful)
                        return@withContext Result.failure(Exception("HTTP ${response.code}"))

                    val json = JSONObject(body)
                    if (!json.optBoolean("success", false))
                        return@withContext Result.failure(Exception(json.optString("error")))

                    val decrypted = decryptResponse(json)
                    val filesArray = decrypted.optJSONArray("files") ?: JSONArray()
                    val list = (0 until filesArray.length()).map { i ->
                        val f = filesArray.getJSONObject(i)
                        UserConfigFile(
                            name = f.optString("name"),
                            path = f.optString("path"),
                            size = f.optLong("size"),
                            modified = f.optDouble("modified"),
                            type = f.optString("type"),
                            category = f.optString("category").takeIf { it.isNotEmpty() },
                            protocol = f.optString("protocol").takeIf { it.isNotEmpty() },
                            config = f.optString("config").takeIf { it.isNotEmpty() },
                            content = f.optString("content", ""),
                            group = f.optString("group").takeIf { it.isNotEmpty() }
                        )
                    }
                    Result.success(list)
                }
            } catch (e: Exception) { Result.failure(e) }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // UPDATE USER  (зашифрованный payload)
    // POST /api/admin/users/<id>/update
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun updateUser(userId: Int, req: UpdateUserRequest): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val payload = JSONObject().apply {
                    req.email?.let { put("email", it) }
                    req.tariff?.let { put("tariff", it) }
                    req.email_confirmed?.let { put("email_confirmed", it) }
                    req.is_active?.let { put("is_active", it) }
                    req.used_traffic?.let { put("used_traffic", it) }
                    req.traffic_limit?.let { put("traffic_limit", it) }
                }
                val body = encryptedBody(payload).toRequestBody(jsonMediaType)
                val request = createRequestBuilder("$baseUrl/api/admin/users/$userId/update")
                    .post(body).build()

                client.newCall(request).execute().use { response ->
                    val json = JSONObject(response.body?.string() ?: "{}")
                    if (json.optBoolean("success", false)) Result.success(true)
                    else Result.failure(Exception(json.optString("error", "Update failed")))
                }
            } catch (e: Exception) { Result.failure(e) }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // RESET PASSWORD  (зашифрованный payload)
    // POST /api/admin/users/<id>/reset-password
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun resetPassword(userId: Int, newPassword: String, userEmail: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                // Хешируем пароль так же как при регистрации
                val hashed = PasswordHasher.hashForServer(newPassword, userEmail)
                val payload = JSONObject().apply { put("new_password", hashed) }
                val body = encryptedBody(payload).toRequestBody(jsonMediaType)
                val request = createRequestBuilder("$baseUrl/api/admin/users/$userId/reset-password")
                    .post(body).build()

                client.newCall(request).execute().use { response ->
                    val json = JSONObject(response.body?.string() ?: "{}")
                    if (json.optBoolean("success", false)) Result.success(true)
                    else Result.failure(Exception(json.optString("error", "Reset failed")))
                }
            } catch (e: Exception) { Result.failure(e) }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // SEND PASSWORD RESET EMAIL
    // POST /api/admin/users/<id>/send-reset-email
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun sendPasswordResetEmail(userId: Int, email: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val payload = JSONObject().apply { put("email", email) }
                val body = encryptedBody(payload).toRequestBody(jsonMediaType)
                val request = createRequestBuilder("$baseUrl/api/admin/users/$userId/send-reset-email")
                    .post(body).build()

                client.newCall(request).execute().use { response ->
                    val json = JSONObject(response.body?.string() ?: "{}")
                    if (json.optBoolean("success", false)) Result.success(true)
                    else Result.failure(Exception(json.optString("error", "Failed to send email")))
                }
            } catch (e: Exception) { Result.failure(e) }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // DELETE USER
    // DELETE /api/admin/users/<id>
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun deleteUser(userId: Int): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val request = createRequestBuilder("$baseUrl/api/admin/users/$userId")
                .delete().build()
            client.newCall(request).execute().use { response ->
                val json = JSONObject(response.body?.string() ?: "{}")
                if (json.optBoolean("success", false)) Result.success(true)
                else Result.failure(Exception(json.optString("error", "Delete failed")))
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STATS  (зашифрованный ответ)
    // GET /api/admin/users/<id>/stats
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun getUserStats(userId: Int): Result<UserStats> = withContext(Dispatchers.IO) {
        try {
            val request = createRequestBuilder("$baseUrl/api/admin/users/$userId/stats")
                .get().build()
            client.newCall(request).execute().use { response ->
                val json = JSONObject(response.body?.string() ?: "{}")
                if (!json.optBoolean("success", false))
                    return@withContext Result.failure(Exception(json.optString("error")))

                val data = if (json.optBoolean("enc", false)) {
                    decryptResponse(json)
                } else {
                    json.optJSONObject("data") ?: JSONObject()
                }

                Result.success(
                    UserStats(
                        user_id = data.optInt("user_id"),
                        total_traffic = data.optLong("total_traffic"),
                        used_traffic = data.optLong("used_traffic"),
                        last_active = data.optString("last_active").takeIf { it.isNotEmpty() },
                        total_connections = data.optInt("total_connections"),
                        active_connections = data.optInt("active_connections"),
                        last_login = data.optString("last_login").takeIf { it.isNotEmpty() },
                        login_count = data.optInt("login_count"),
                        last_ip = data.optString("last_ip").takeIf { it.isNotEmpty() }
                    )
                )
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PAYMENTS  (зашифрованный ответ)
    // GET /api/admin/users/<id>/payments
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun getUserPayments(userId: Int): Result<List<UserPayment>> = withContext(Dispatchers.IO) {
        try {
            val request = createRequestBuilder("$baseUrl/api/admin/users/$userId/payments")
                .get().build()
            client.newCall(request).execute().use { response ->
                val json = JSONObject(response.body?.string() ?: "{}")
                if (!json.optBoolean("success", false))
                    return@withContext Result.failure(Exception(json.optString("error")))

                val arr = if (json.optBoolean("enc", false)) {
                    decryptResponse(json).optJSONArray("payments") ?: JSONArray()
                } else {
                    json.optJSONArray("data") ?: JSONArray()
                }

                val payments = (0 until arr.length()).map { i ->
                    val p = arr.getJSONObject(i)
                    UserPayment(
                        id = p.optInt("id"),
                        amount = p.optString("amount"),
                        currency = p.optString("currency", "RUB"),
                        status = p.optString("status"),
                        created_at = p.optString("created_at"),
                        description = p.optString("description").takeIf { it.isNotEmpty() },
                        tariff = p.optString("tariff").takeIf { it.isNotEmpty() },
                        period = p.optString("period").takeIf { it.isNotEmpty() },
                        source = p.optString("source").takeIf { it.isNotEmpty() }
                    )
                }
                Result.success(payments)
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LOGS
    // GET /api/admin/users/<id>/logs
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun getUserLogs(userId: Int): Result<List<UserLog>> = withContext(Dispatchers.IO) {
        try {
            val request = createRequestBuilder("$baseUrl/api/admin/users/$userId/logs")
                .get().build()
            client.newCall(request).execute().use { response ->
                val json = JSONObject(response.body?.string() ?: "{}")
                if (!json.optBoolean("success", false))
                    return@withContext Result.failure(Exception(json.optString("error")))

                val arr = if (json.optBoolean("enc", false)) {
                    decryptResponse(json).optJSONArray("logs") ?: JSONArray()
                } else {
                    json.optJSONArray("data") ?: JSONArray()
                }

                val logs = (0 until arr.length()).map { i ->
                    val l = arr.getJSONObject(i)
                    UserLog(
                        id = l.optInt("id"),
                        action = l.optString("action"),
                        details = l.optString("details"),
                        created_at = l.optString("created_at"),
                        ip_address = l.optString("ip_address").takeIf { it.isNotEmpty() }
                    )
                }
                Result.success(logs)
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SUBSCRIPTION MANAGEMENT  (зашифрованный payload)
    // POST /api/admin/users/<id>/subscription
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun updateSubscription(userId: Int, action: String, tariff: String? = null): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val payload = JSONObject().apply {
                    put("action", action)
                    tariff?.let { put("tariff", it) }
                }
                val body = encryptedBody(payload).toRequestBody(jsonMediaType)
                val request = createRequestBuilder("$baseUrl/api/admin/users/$userId/subscription")
                    .post(body).build()

                client.newCall(request).execute().use { response ->
                    val json = JSONObject(response.body?.string() ?: "{}")
                    if (json.optBoolean("success", false)) Result.success(true)
                    else Result.failure(Exception(json.optString("error", "Subscription update failed")))
                }
            } catch (e: Exception) { Result.failure(e) }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // TRAFFIC MANAGEMENT  (зашифрованный payload)
    // POST /api/admin/users/<id>/traffic
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun updateTraffic(userId: Int, deltaBytes: Long, reset: Boolean = false): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val payload = JSONObject().apply {
                    put("delta_bytes", deltaBytes)
                    put("reset", reset)
                }
                val body = encryptedBody(payload).toRequestBody(jsonMediaType)
                val request = createRequestBuilder("$baseUrl/api/admin/users/$userId/traffic")
                    .post(body).build()

                client.newCall(request).execute().use { response ->
                    val json = JSONObject(response.body?.string() ?: "{}")
                    if (json.optBoolean("success", false)) Result.success(true)
                    else Result.failure(Exception(json.optString("error", "Traffic update failed")))
                }
            } catch (e: Exception) { Result.failure(e) }
        }
}
