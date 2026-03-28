package ru.acestar.vpn.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import ru.acestar.vpn.data.model.AuthData
import ru.acestar.vpn.data.model.RegRequest
import ru.acestar.vpn.data.model.ConfirmEmail
import ru.acestar.vpn.data.model.GetConfigsRequest
import ru.acestar.vpn.utils.PasswordHasher
import java.util.Date
import java.sql.Date as SqlDate

class AuthApiService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private var pendinLoginDate: Date = Date()

    // Базовый URL вашего Flask API
    private val baseUrl = "http://94.249.239.35:5001"

    // Токен авторизации
    private var authToken: String? = null

    fun setAuthToken(token: String) {
        authToken = token
    }

    /**
     * @param passwordHash SHA-256 от [PasswordHasher.hashForServer], не пароль в открытом виде.
     */
    suspend fun login(
        email: String,
        passwordHash: String
    ): Result<AuthData> = withContext(Dispatchers.IO) {
        try {
            val utilDate = Date()
            val sqlDate = SqlDate(utilDate.time)

            val jsonBody = JSONObject().apply {
                put("email", email)
                put("password", passwordHash)
                put("date", sqlDate)
            }

            val requestBuilder = Request.Builder()
                .url("$baseUrl/api/v1/login")
                .post(jsonBody.toString().toRequestBody(jsonMediaType))
                .addHeader("Content-Type", "application/json")

            authToken?.let { token ->
                requestBuilder.addHeader("Authorization", "Bearer $token")
            }

            val request = requestBuilder.build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                val json = JSONObject(responseBody ?: "{}")

                if (!response.isSuccessful) {
                    val errorMsg = json.optString("error", "HTTP ${response.code}: ${response.message}")
                    return@withContext Result.failure(Exception(errorMsg))
                }

                val data = when {
                    json.has("data") && !json.isNull("data") -> json.getJSONObject("data")
                    else -> json
                }

                val authData = parseAuthDataFromJson(data, email)
                Result.success(authData)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createUser(
        email: String,
        password: String,
        isConfirmed: Boolean,
        createDate: Date,
        tariff: String,
        isNew: Boolean
    ): Result<AuthData> = withContext(Dispatchers.IO) {
        try {
            val passwordHashed = PasswordHasher.hashForServer(password, email)
            // Создаем запрос
            val RegRequest = RegRequest(
                email = email,
                password = passwordHashed,
                isConfirmed = isConfirmed,
                createDate = createDate as SqlDate,
                tariff = tariff,
                isNew = isNew
            )

            // Конвертируем в JSON
            val jsonBody = JSONObject().apply {
                put("email", RegRequest.email)
                put("password", RegRequest.password)
                put("isConfirmed", RegRequest.isConfirmed)
                put("createDate", RegRequest.createDate)
                put("tariff", RegRequest.tariff)
                put("isNew", RegRequest.isNew)
            }

            // Создаем HTTP запрос
            val requestBuilder = Request.Builder()
                .url("$baseUrl/api/v1/reg")
                .post(jsonBody.toString().toRequestBody(jsonMediaType))
                .addHeader("Content-Type", "application/json")

            // Добавляем токен авторизации, если он есть
            authToken?.let { token ->
                requestBuilder.addHeader("Sign Up", "Bearer $token")
            }

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
                    val data = when {
                        json.has("data") && !json.isNull("data") -> json.getJSONObject("data")
                        else -> json
                    }

                    val responseEmail = when {
                        data.has("email") && data.optString("email").isNotBlank() -> data.optString("email")
                        json.has("email") && json.optString("email").isNotBlank() -> json.optString("email")
                        else -> ""
                    }

                    // Сервер часто не дублирует email в теле ответа — используем адрес из запроса регистрации.
                    val authData = parseAuthDataFromJson(data, responseEmail.ifBlank { email })
                    Result.success(authData)
                } else {
                    val error = json.optString("error", "Unknown error")
                    Result.failure(Exception(error))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** После первой успешной оплаты: isNew = 0 на сервере и локально. */
    suspend fun markUserNotNew(accessToken: String?): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply { put("is_new", false) }
            val request = Request.Builder()
                .url("$baseUrl/api/v1/user/mark-not-new")
                .post(body.toString().toRequestBody(jsonMediaType))
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Content-Type", "application/json")
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val err = try {
                        JSONObject(text).optString("error", "HTTP ${response.code}")
                    } catch (_: Exception) {
                        "HTTP ${response.code}"
                    }
                    return@withContext Result.failure(Exception(err))
                }
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getConfigs(
        userId: Int
    ): Result<AuthData> = withContext(Dispatchers.IO) {
        try {
            // Создаем запрос
            val GetConfigsRequest = GetConfigsRequest(
                userId = userId,
            )

            // Конвертируем в JSON
            val jsonBody = JSONObject().apply {
                put("userId", GetConfigsRequest.userId)
            }

            // Создаем HTTP запрос
            val requestBuilder = Request.Builder()
                .url("$baseUrl/api/v1/getConfigs")
                .post(jsonBody.toString().toRequestBody(jsonMediaType))
                .addHeader("Content-Type", "application/json")

            // Добавляем токен авторизации, если он есть
            authToken?.let { token ->
                requestBuilder.addHeader("Auth", "Bearer $token")
            }

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
                    val data = when {
                        json.has("data") && !json.isNull("data") -> json.getJSONObject("data")
                        else -> json
                    }

                    val email = when {
                        data.has("email") -> data.optString("email")
                        json.has("email") -> json.optString("email")
                        else -> ""
                    }

                    val status = when {
                        data.has("status") -> data.optString("status")
                        json.has("status") -> json.optString("status")
                        else -> "ok"
                    }

                    val isConfirmed = when {
                        data.has("isConfirmed") -> data.optBoolean("isConfirmed")
                        json.has("isConfirmed") -> json.optBoolean("isConfirmed")
                        else -> false
                    }

                    val authData = AuthData(
                        email = email,
                        isConfirmed = isConfirmed
                    )
                    Result.success(authData)
                } else {
                    val error = json.optString("error", "Unknown error")
                    Result.failure(Exception(error))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun confirmEmail(
        email: String,
        isConfirmed: Boolean,
    ): Result<ConfirmEmail> = withContext(Dispatchers.IO) {
        try {
            // Создаем запрос
            val ConfirmEmail = ConfirmEmail(
                email = email,
                isConfirmed = isConfirmed
            )

            // Конвертируем в JSON
            val jsonBody = JSONObject().apply {
                put("email", ConfirmEmail.email)
                put("isConfirmed", ConfirmEmail.isConfirmed)
            }

            // Создаем HTTP запрос
            val requestBuilder = Request.Builder()
                .url("$baseUrl/api/v1/reg/confirm")
                .post(jsonBody.toString().toRequestBody(jsonMediaType))
                .addHeader("Content-Type", "application/json")

            // Добавляем токен авторизации, если он есть
            authToken?.let { token ->
                requestBuilder.addHeader("Sign Up", "Bearer $token")
            }

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
                    // Некоторые бэкенды могут не возвращать поле "status" или вкладывать данные иначе.
                    // Делаем парсинг более устойчивым, чтобы избежать "No value for status".
                    val data = when {
                        json.has("data") && !json.isNull("data") -> json.getJSONObject("data")
                        else -> json
                    }

                    val email = when {
                        data.has("email") -> data.optString("email")
                        json.has("email") -> json.optString("email")
                        else -> ""
                    }

                    val isConfirmed = when {
                        data.has("isConfirmed") -> data.optBoolean("isConfirmed")
                        json.has("isConfirmed") -> json.optBoolean("isConfirmed")
                        else -> false
                    }

                    val authData = ConfirmEmail(
                        email = email,
                        isConfirmed = isConfirmed
                    )
                    Result.success(authData)
                } else {
                    val error = json.optString("error", "Unknown error")
                    Result.failure(Exception(error))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun loginUser(
        email: String,
        isConfirmed: Boolean,
    ): Result<ConfirmEmail> = withContext(Dispatchers.IO) {
        try {
            // Создаем запрос
            val ConfirmEmail = ConfirmEmail(
                email = email,
                isConfirmed = isConfirmed
            )

            // Конвертируем в JSON
            val jsonBody = JSONObject().apply {
                put("email", ConfirmEmail.email)
                put("isConfirmed", ConfirmEmail.isConfirmed)
            }

            // Создаем HTTP запрос
            val requestBuilder = Request.Builder()
                .url("$baseUrl/api/v1/reg/confirm")
                .post(jsonBody.toString().toRequestBody(jsonMediaType))
                .addHeader("Content-Type", "application/json")

            // Добавляем токен авторизации, если он есть
            authToken?.let { token ->
                requestBuilder.addHeader("Sign Up", "Bearer $token")
            }

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
                    // Некоторые бэкенды могут не возвращать поле "status" или вкладывать данные иначе.
                    // Делаем парсинг более устойчивым, чтобы избежать "No value for status".
                    val data = when {
                        json.has("data") && !json.isNull("data") -> json.getJSONObject("data")
                        else -> json
                    }

                    val email = when {
                        data.has("email") -> data.optString("email")
                        json.has("email") -> json.optString("email")
                        else -> ""
                    }

                    val isConfirmed = when {
                        data.has("isConfirmed") -> data.optBoolean("isConfirmed")
                        json.has("isConfirmed") -> json.optBoolean("isConfirmed")
                        else -> false
                    }

                    val authData = ConfirmEmail(
                        email = email,
                        isConfirmed = isConfirmed
                    )
                    Result.success(authData)
                } else {
                    val error = json.optString("error", "Unknown error")
                    Result.failure(Exception(error))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    suspend fun sendConfirmEmailCode(
        email: String,
        isConfirmed: Boolean,
    ): Result<ConfirmEmail> = withContext(Dispatchers.IO) {
        try {
            // Создаем запрос
            val ConfirmEmail = ConfirmEmail(
                email = email,
                isConfirmed = isConfirmed
            )

            // Конвертируем в JSON
            val jsonBody = JSONObject().apply {
                put("email", ConfirmEmail.email)
                put("isConfirmed", ConfirmEmail.isConfirmed)
            }

            // Создаем HTTP запрос
            val requestBuilder = Request.Builder()
                .url("$baseUrl/api/v1/reg/confirm")
                .post(jsonBody.toString().toRequestBody(jsonMediaType))
                .addHeader("Content-Type", "application/json")

            // Добавляем токен авторизации, если он есть
            authToken?.let { token ->
                requestBuilder.addHeader("Sign Up", "Bearer $token")
            }

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
                    // Некоторые бэкенды могут не возвращать поле "status" или вкладывать данные иначе.
                    // Делаем парсинг более устойчивым, чтобы избежать "No value for status".
                    val data = when {
                        json.has("data") && !json.isNull("data") -> json.getJSONObject("data")
                        else -> json
                    }

                    val email = when {
                        data.has("email") -> data.optString("email")
                        json.has("email") -> json.optString("email")
                        else -> ""
                    }

                    val isConfirmed = when {
                        data.has("isConfirmed") -> data.optBoolean("isConfirmed")
                        json.has("isConfirmed") -> json.optBoolean("isConfirmed")
                        else -> false
                    }

                    val authData = ConfirmEmail(
                        email = email,
                        isConfirmed = isConfirmed
                    )
                    Result.success(authData)
                } else {
                    val error = json.optString("error", "Unknown error")
                    Result.failure(Exception(error))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseAuthDataFromJson(data: JSONObject, fallbackEmail: String): AuthData {
        val email = when {
            data.has("email") && data.optString("email").isNotBlank() -> data.optString("email")
            else -> fallbackEmail
        }
        val token = when {
            data.has("token") -> data.optString("token", "")
            else -> ""
        }.takeIf { it.isNotBlank() }
        return AuthData(
            email = email,
            isConfirmed = data.optBoolean("is_confirmed", data.optBoolean("isConfirmed", false)),
            isNew = data.optBoolean("is_new", data.optBoolean("isNew", true)),
            token = token,
            subsEndDate = null,
            isAdmin = data.optBoolean("is_admin", data.optBoolean("isAdmin", false))
        )
    }

    private fun parseLongNullable(obj: JSONObject, vararg keys: String): Long? {
        for (k in keys) {
            if (!obj.has(k)) continue
            when (val v = obj.get(k)) {
                is Number -> {
                    val l = v.toLong()
                    if (l > 0L) return l
                }
                is String -> v.toLongOrNull()?.let { if (it > 0L) return it }
            }
        }
        return null
    }
}