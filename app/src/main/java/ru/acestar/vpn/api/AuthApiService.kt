package ru.acestar.vpn.api

import android.util.Log
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import ru.acestar.vpn.VpnApplication
import ru.acestar.vpn.data.dao.UserDao
import ru.acestar.vpn.data.model.AuthData
import ru.acestar.vpn.data.model.RegRequest
import ru.acestar.vpn.data.model.ConfirmEmail
import ru.acestar.vpn.data.model.CurrentSession
import ru.acestar.vpn.data.model.User
import ru.acestar.vpn.data.model.UserUploadedConfig
import ru.acestar.vpn.data.model.admin.UserConfigFile
import ru.acestar.vpn.data.repository.SessionManager
import ru.acestar.vpn.data.repository.UserRepository
import ru.acestar.vpn.utils.CryptoHelper
import ru.acestar.vpn.utils.PasswordHasher
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale
import java.sql.Date as SqlDate

class AuthApiService(private val sessionManager: SessionManager) {
    companion object { private val refreshMutex = Mutex() }
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private suspend fun getToken(): String? {
        val token = sessionManager.getAccessToken()
        return token
    }



    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private var pendinLoginDate: Date = Date()

    // Базовый URL вашего Flask API
    private val baseUrl = "https://usavpn.duckdns.org"

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
//                println("Содержимое data: ${data.toString()}")

                val authData = parseAuthDataFromJson(data, email)

//                println("Данные для получения файлов конф: ${email}, ${authData.token}")
                Result.success(authData)


            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun emailRequest(path: String, payload: JSONObject): Result<JSONObject> = withContext(Dispatchers.IO) {
        runCatching {
            val builder = Request.Builder().url("$baseUrl/api/v1/$path")
                .post(payload.toString().toRequestBody(jsonMediaType))
            sessionManager.getAccessToken()?.let { builder.header("Authorization", "Bearer $it") }
            client.newCall(builder.build()).execute().use { response ->
                val json = JSONObject(response.body?.string().orEmpty())
                if (!response.isSuccessful || !json.optBoolean("success")) {
                    throw Exception(json.optString("error", "Не удалось выполнить запрос"))
                }
                json
            }
        }
    }

    suspend fun createUser(
        email: String,
        password: String,
        createDate: Date,
        verificationToken: String,
    ): Result<AuthData> = withContext(Dispatchers.IO) {
        try {
            val passwordHashed = PasswordHasher.hashForServer(password, email)
            // Создаем запрос
            val RegRequest = RegRequest(
                email = email,
                password = passwordHashed,
                createDate = SqlDate(createDate.time),
            )

            // Конвертируем в JSON
            val jsonBody = JSONObject().apply {
                put("email", RegRequest.email)
                put("password", RegRequest.password)
                put("createDate", RegRequest.createDate)
                put("verificationToken", verificationToken)
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
                        else -> ""
                    }

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

    suspend fun confirmEmail(
        email: String,
        isConfirmed: Boolean,
    ): Result<ConfirmEmail> = withContext(Dispatchers.IO) {
        try {
            val token = getToken()
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
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")

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

    suspend fun getClientFilesList(email: String, token: String?): Result<List<UserConfigFile>> =
        withContext(Dispatchers.IO) {
            try {
                println("Получение файлов конфигурации")
                token?: return@withContext Result.failure(

                        Exception("Нет токена для получения конфигурации.")
                    )

                val email = JSONObject().apply{
                    put("email", email)
                }
                val encryptedEmail = CryptoHelper.encryptData(email.toString(), token)

                val jsonBody = JSONObject().apply {
                    put("data", encryptedEmail)
                }
//                println("Передаваемые данные для получения файлов: ${jsonBody}")

                val request = Request.Builder()
                    .url("$baseUrl/api/v1/users/configs")
                    .post(jsonBody.toString().toRequestBody(jsonMediaType))
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .build()

                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    println("📥 Response code: ${response.code}")

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
//                        println("🔓 Decrypted data: $decryptedJson")

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
                                content = fileJson.optString("content", null),
                                group = fileJson.optString("group", null)
                                    .takeIf { !it.isNullOrBlank() }
                            )
                            filesList.add(file)
                        }
//                        println(filesList.toString())
                        Result.success(filesList)
                    } else {
                        Result.failure(Exception("Expected encrypted data"))
                    }
                }
            } catch (e: Exception) {
//                Log.d("Error getting files list $e")
                println("Ошибка получения файлов: ${e}")
                Result.failure(e)
            }
        }

    suspend fun getUserData(email: String, token: String?, userRepository: UserRepository): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                println("Обновление данных пользователя")
                val accessToken = token ?: return@withContext Result.failure(
                    Exception("Нет токена для получения конфигурации.")
                )
                val emailJSON = JSONObject().apply{
                    put("email", email)
                }
                val encryptedEmail = CryptoHelper.encryptData(emailJSON.toString(), accessToken)

                val jsonBody = JSONObject().apply {
                    put("data", encryptedEmail)
                }

                val request = Request.Builder()
                    .url("$baseUrl/api/v1/login/update")
                    .post(jsonBody.toString().toRequestBody(jsonMediaType))
                    .addHeader("Authorization", "Bearer $accessToken")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .build()

                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    println("📥 Response code: ${response.code}")

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
                    val rawData = jsonResponse.optString("data", null)


                    if (rawData == null || rawData === JSONObject.NULL) {
                        return@withContext Result.failure(Exception("Ответ без поля data"))
                    }

                    if (encrypted && rawData != null) {
                        // ✅ 1. Расшифровываем данные
                        val decryptedJson = CryptoHelper.decryptData(rawData, token)

                        // ✅ 2. Парсим расшифрованный JSON
                        val userData = JSONObject(decryptedJson)

                        val cachedUser = sessionManager.getCurrentUser()
                        val subsEndDateTimestamp = parseServerTime(userData.opt("subsEndDate"))
                            ?: cachedUser?.subsEndDate ?: 0L
                        val tokenExpiryTimestamp = ru.acestar.vpn.utils.JwtTokenUtils.extractExpiryMillis(accessToken)
                            ?: parseServerTime(userData.opt("tokenExpiry"))
                            ?: sessionManager.currentSession.first()?.tokenExpiry ?: 0L
                        val createdAtTimestamp = parseServerTime(userData.opt("createdAt"))
                            ?: cachedUser?.createdAt ?: System.currentTimeMillis()
                        val user = User(
                            id = cachedUser?.id ?: userData.optLong("user_id"),
                            createdAt = createdAtTimestamp,
                            email = email,
                            tariff = userData.optString("tariff"),
                            emailConfirmed = userData.optBoolean("isConfirmed"),
                            isNew = userData.optBoolean("isNew"),
                            isActive = userData.optBoolean("isActive"),
                            isAdmin = userData.optBoolean("isAdmin"),
                            subsEndDate = subsEndDateTimestamp,
                            usedTrafficBytes = userData.optLong("used_traffic", 0L),
                            trafficLimit = userData.optLong("traffic_limit", 0L)
                        )

                        val session = CurrentSession(
                            userId = user.id,
                            email = email,
                            tariff = user.tariff,
                            subsEndDate = subsEndDateTimestamp,
                            token = accessToken,
                            isConfirmed = user.emailConfirmed,
                            isActive = user.isActive,
                            tokenExpiry = tokenExpiryTimestamp,
                            createdAt = createdAtTimestamp,
                            isAdmin = user.isAdmin,
                            isNew = user.isNew,
                            usedTrafficBytes = user.usedTrafficBytes,
                            trafficLimit = user.trafficLimit
                        )

                        userRepository.updateUser(user)
                        sessionManager.updateCurrentSession(session)
                    }

                    return@withContext Result.success(true)
                }
            } catch (e: Exception) {
//                Log.d("Error getting files list $e")
                println("Ошибка обновления данных: ${e}")
                Result.failure(e)
            }
        }

    private fun parseServerTime(value: Any?): Long? {
        if (value == null || value == JSONObject.NULL) return null
        val text = value.toString()
        (if (value is Number) value.toLong() else text.toLongOrNull())?.let {
            return if (it >= 1_000_000_000_000L) it else it * 1000L
        }
        return runCatching { java.time.Instant.parse(text).toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching {
                java.time.LocalDateTime.parse(text.replace(' ', 'T'))
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.getOrNull()
            ?: runCatching {
                java.time.LocalDate.parse(text).atStartOfDay(java.time.ZoneId.systemDefault())
                    .toInstant().toEpochMilli()
            }.getOrNull()
    }

    private fun parseAuthDataFromJson(data: JSONObject, fallbackEmail: String): AuthData {
        val email = when {
            data.optString("email").isNotBlank() -> data.optString("email")
            else -> fallbackEmail
        }
        val token = when {
            data.has("token") -> data.optString("token", "")
            else -> ""
        }.takeIf { it.isNotBlank() }
        require(!token.isNullOrBlank()) { "Сервер не вернул токен авторизации" }
        val is_confirmed = when {
            data.optBoolean("isConfirmed") -> data.optBoolean("isConfirmed")
            else -> false
        }

        val is_new = when {
            data.optBoolean("isNew") -> data.optBoolean("isNew")
            else -> false
        }

        val subsEndDateTimestamp = parseServerTime(data.opt("subsEndDate")) ?: 0L
        val is_admin = when {
            data.optBoolean("isAdmin") -> data.optBoolean("isAdmin")
            else -> false
        }


        return AuthData(
            id = data.optLong("id", data.optLong("user_id", 0L)).also {
                require(it > 0L) { "Сервер не вернул ID пользователя" }
            },
            email = email,
            isConfirmed = data.optBoolean("isConfirmed"),
            isNew = data.optBoolean("isNew"),
            isActive = data.optBoolean("isActive"),
            token = token,
            tariff = data.optString("tariff"),
            subsEndDate = subsEndDateTimestamp,
            createdAt = parseServerTime(data.opt("createdAt")) ?: System.currentTimeMillis(),
            isAdmin = data.optBoolean("isAdmin"),
            usedTraffic = data.optLong("used_traffic", 0L),
            trafficLimit = data.optLong("traffic_limit", 0L)
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ОБНОВЛЕНИЕ ТОКЕНА (фоновый рефреш без переавторизации)
    // POST /api/v1/token/refresh
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Обновляет JWT токен в фоне, используя текущий токен.
     * Вызывается при onResume если срок токена подходит к концу.
     * При успехе обновляет SessionManager без переавторизации.
     * При ошибке — оставляет старый токен, навигация к логину не происходит.
     */
    suspend fun refreshTokenIfNeeded(): Result<String> = refreshMutex.withLock {
        val session = sessionManager.currentSession.first()
            ?: return@withLock Result.failure(Exception("Нет сессии"))
        val token = session.token?.takeIf { it.isNotBlank() }
            ?: return@withLock Result.failure(Exception("Нет токена"))
        if (session.tokenExpiry - System.currentTimeMillis() > 24L * 60 * 60 * 1000) {
            Result.success(token)
        } else {
            refreshToken()
        }
    }
    suspend fun refreshToken(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val currentToken = getToken()
                ?: return@withContext Result.failure(Exception("Нет токена для обновления"))

            val request = Request.Builder()
                .url("$baseUrl/api/v1/token/refresh")
                .post("{}".toRequestBody(jsonMediaType))
                .addHeader("Authorization", "Bearer $currentToken")
                .addHeader("Content-Type", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: "{}"
                val json = JSONObject(body)

                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("Token refresh failed: HTTP ${response.code}")
                    )
                }

                val data = json.optJSONObject("data") ?: json
                val newToken = data.optString("token", "")
                    .takeIf { it.isNotBlank() }
                    ?: return@withContext Result.failure(Exception("Пустой токен в ответе"))

                // Извлекаем новое время истечения из JWT или из ответа
                val expiryMs = ru.acestar.vpn.utils.JwtTokenUtils.extractExpiryMillis(newToken)
                    ?: parseServerTime(data.opt("tokenExpiry"))
                    ?: (System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000)

                if (!sessionManager.replaceTokenIfCurrent(currentToken, newToken, expiryMs)) {
                    return@withContext Result.failure(Exception("Сессия изменилась во время обновления"))
                }
                Result.success(newToken)
            }
        } catch (e: Exception) {
            Log.w("AuthApiService", "refreshToken failed (non-critical): ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Комплексное обновление данных при onResume:
     * 1. Если токен истекает в течение 1 дня — обновляем его в фоне
     * 2. Запрашиваем актуальные данные пользователя с сервера
     * 3. Всё в фоне, без блокировки UI, без переавторизации при ошибке
     */
    suspend fun refreshOnResume(userRepository: UserRepository): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                refreshTokenIfNeeded()
                val session = sessionManager.getCurrentUser()
                    ?: return@withContext Result.failure(Exception("Не авторизован"))
                val token = sessionManager.getAccessToken()
                    ?: return@withContext Result.failure(Exception("Нет токена"))
                // Получаем актуальный токен (мог обновиться выше)
                val freshToken = sessionManager.getAccessToken() ?: token

                // Запрашиваем актуальные данные пользователя
                getUserData(session.email, freshToken, userRepository)
            } catch (e: Exception) {
                Log.w("AuthApiService", "refreshOnResume failed: ${e.message}")
                Result.failure(e)
            }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // ОТПРАВКА ПОЛЬЗОВАТЕЛЬСКОГО КОНФИГА НА СЕРВЕР (синхронизация)
    // POST /api/v1/user/configs/upload
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Отправляет пользовательский конфиг на сервер (зашифрованно).
     * При ошибке — возвращает failure, вызывающий код ничего не делает.
     */
    suspend fun uploadUserConfig(
        name: String,
        type: String,
        content: String,
        serverHost: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val token = getToken() ?: return@withContext Result.failure(Exception("Нет токена"))

            val payload = JSONObject().apply {
                put("name", name)
                put("type", type)
                put("server_host", serverHost)
                put("content", content)
            }
            val encrypted = CryptoHelper.encryptData(payload.toString(), token)
            val jsonBody = JSONObject().apply { put("data", encrypted) }.toString()

            val request = Request.Builder()
                .url("$baseUrl/api/v1/user/configs/upload")
                .post(jsonBody.toRequestBody(jsonMediaType))
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                val json = JSONObject(response.body?.string() ?: "{}")
                if (response.isSuccessful && json.optBoolean("success", false)) {
                    Result.success(true)
                } else {
                    Result.failure(Exception(json.optString("error", "Upload failed")))
                }
            }
        } catch (e: Exception) {
            Log.w("AuthApiService", "uploadUserConfig failed: ${e.message}")
            Result.failure(e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ПОЛУЧЕНИЕ ПОЛЬЗОВАТЕЛЬСКИХ КОНФИГОВ (загруженных самим пользователем)
    // POST /api/v1/user/configs/list
    // Возвращает ТОЛЬКО конфиги, добавленные пользователем через configs/upload,
    // а не серверные (авто) конфиги из /api/v1/users/configs.
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun getUserUploadedConfigs(
        email: String,
        token: String?
    ): Result<List<UserUploadedConfig>> = withContext(Dispatchers.IO) {
        try {
            val accessToken = token ?: return@withContext Result.failure(
                Exception("Нет токена для получения пользовательских конфигов")
            )

            val payload = JSONObject().apply { put("email", email) }
            val encrypted = CryptoHelper.encryptData(payload.toString(), accessToken)
            val jsonBody = JSONObject().apply { put("data", encrypted) }.toString()

            val request = Request.Builder()
                .url("$baseUrl/api/v1/user/configs/list")
                .post(jsonBody.toRequestBody(jsonMediaType))
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()

                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("HTTP ${response.code}: ${response.message}")
                    )
                }
                if (responseBody.isNullOrEmpty()) {
                    return@withContext Result.failure(Exception("Empty response"))
                }

                val jsonResponse = JSONObject(responseBody)
                if (!jsonResponse.optBoolean("success", false)) {
                    return@withContext Result.failure(
                        Exception(jsonResponse.optString("error", "Unknown error"))
                    )
                }

                val enc = jsonResponse.optBoolean("enc", false)
                val rawData = jsonResponse.optString("data", null)
                if (!enc || rawData.isNullOrEmpty()) {
                    return@withContext Result.success(emptyList())
                }

                val decryptedJson = CryptoHelper.decryptData(rawData, accessToken)
                val data = JSONObject(decryptedJson)
                val configsArray = data.optJSONArray("configs") ?: JSONArray()

                val configs = mutableListOf<UserUploadedConfig>()
                for (i in 0 until configsArray.length()) {
                    val obj = configsArray.getJSONObject(i)
                    val content = obj.optString("content", "")
                    if (content.isBlank()) continue
                    configs.add(
                        UserUploadedConfig(
                            name = obj.optString("name", ""),
                            type = obj.optString("type", ""),
                            serverHost = obj.optString("server_host", ""),
                            content = content
                        )
                    )
                }
                Result.success(configs)
            }
        } catch (e: Exception) {
            Log.w("AuthApiService", "getUserUploadedConfigs failed: ${e.message}")
            Result.failure(e)
        }
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

    // ─────────────────────────────────────────────────────────────────────────
    // СИНХРОНИЗАЦИЯ ТРАФИКА С СЕРВЕРОМ
    // POST /api/v1/user/sync-traffic
    // Отправляет текущее потребление трафика на сервер.
    // Сервер обновляет used_traffic и возвращает актуальные данные пользователя.
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun syncTrafficToServer(
        usedBytes: Long,
        userRepository: UserRepository
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val token = getToken() ?: return@withContext Result.failure(
                Exception("Нет токена для синхронизации трафика")
            )

            val payload = JSONObject().apply {
                put("used_traffic", usedBytes)
            }
            val encrypted = CryptoHelper.encryptData(payload.toString(), token)
            val jsonBody = JSONObject().apply { put("data", encrypted) }.toString()

            val request = Request.Builder()
                .url("$baseUrl/api/v1/user/sync-traffic")
                .post(jsonBody.toRequestBody(jsonMediaType))
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: "{}"
                val json = JSONObject(responseBody)

                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("HTTP ${response.code}: ${json.optString("error")}")
                    )
                }

                if (!json.optBoolean("success", false)) {
                    return@withContext Result.failure(
                        Exception(json.optString("error", "Sync failed"))
                    )
                }

                // Если сервер вернул обновлённые данные пользователя — применяем их
                val enc = json.optBoolean("enc", false)
                val rawData = json.optString("data", null)
                if (enc && rawData != null) {
                    try {
                        val decrypted = CryptoHelper.decryptData(rawData, token)
                        val userData = JSONObject(decrypted)
                        val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

                        val subsEndStr = userData.optString("subsEndDate", null)
                        val subsEndDate = subsEndStr?.let {
                            runCatching { format.parse(it)?.time }.getOrNull()
                        }

                        val currentUser = sessionManager.getCurrentUser()
                        if (currentUser != null) {
                            // Доверяем большему значению использованного трафика:
                            // локальному (отправленному) или серверному — что больше.
                            val serverUsed = userData.optLong("used_traffic", usedBytes)
                            val finalUsed = maxOf(usedBytes, serverUsed)
                            val updated = currentUser.copy(
                                tariff = userData.optString("tariff", currentUser.tariff),
                                subsEndDate = subsEndDate ?: currentUser.subsEndDate,
                                usedTrafficBytes = finalUsed,
                                trafficLimit = userData.optLong("traffic_limit", currentUser.trafficLimit),
                                isActive = userData.optBoolean("isActive", currentUser.isActive)
                            )
                            userRepository.updateUser(updated)
                            sessionManager.updateCurrentUser(updated)
                        }
                    } catch (e: Exception) {
                        Log.w("AuthApiService", "syncTraffic: failed to parse server response", e)
                    }
                }

                Result.success(true)
            }
        } catch (e: Exception) {
            Log.e("AuthApiService", "syncTrafficToServer failed", e)
            Result.failure(e)
        }
    }
}

//private fun JSONObject.optLong(name: String, fallback: String): Long {
//    return TODO("Предоставить возвращаемое значение")
//}








