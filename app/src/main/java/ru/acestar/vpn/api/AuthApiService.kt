package ru.acestar.vpn.api

import android.util.Log
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
//                println("Содержимое data: ${data.toString()}")

                val authData = parseAuthDataFromJson(data, email)
                getClientFilesList(email, authData.token)
//                println("Данные для получения файлов конф: ${email}, ${authData.token}")
                Result.success(authData)


            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createUser(
        email: String,
        password: String,
        createDate: Date,
    ): Result<AuthData> = withContext(Dispatchers.IO) {
        try {
            val passwordHashed = PasswordHasher.hashForServer(password, email)
            // Создаем запрос
            val RegRequest = RegRequest(
                email = email,
                password = passwordHashed,
                createDate = createDate as SqlDate,
            )

            // Конвертируем в JSON
            val jsonBody = JSONObject().apply {
                put("email", RegRequest.email)
                put("password", RegRequest.password)
                put("createDate", RegRequest.createDate)
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
                println("Текущий токен: ${token}")
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
                                content = fileJson.optString("content", null)
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
                println("Текущий токен: $accessToken")
                val emailJSON = JSONObject().apply{
                    put("email", email)
                }
                val encryptedEmail = CryptoHelper.encryptData(emailJSON.toString(), accessToken)

                val jsonBody = JSONObject().apply {
                    put("data", encryptedEmail)
                }
                println("Передаваемые данные для получения файлов: ${jsonBody}")

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

                    println(rawData)
                    if (rawData == null || rawData === JSONObject.NULL) {
                        return@withContext Result.failure(Exception("Ответ без поля data"))
                    }

                    if (encrypted && rawData != null) {
                        // ✅ 1. Расшифровываем данные
                        val decryptedJson = CryptoHelper.decryptData(rawData, token)

                        println("🔓 Decrypted data: $decryptedJson")

                        // ✅ 2. Парсим расшифрованный JSON
                        val userData = JSONObject(decryptedJson)

                        val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

                        val subsEndDate = userData.optString("subsEndDate")
                        val date: Date = format.parse(subsEndDate)!!
                        val subsEndDateTimestamp: Long = date.time

                        val tokenExpiry = userData.optString("tokenExpiry")
                        val dateToken: Date = format.parse(tokenExpiry)!!
                        val tokenExpiryTimestamp: Long = dateToken.time

                        val createdAtExpiry = userData.optString("createdAt")
                        val createdAtToken: Date = format.parse(createdAtExpiry)!!
                        val createdAtTimestamp: Long = createdAtToken.time


                        val user = User(
                            id = userData.optLong("user_id"),
                            email = email,
                            tariff = userData.optString("tariff"),
                            emailConfirmed = userData.optBoolean("isConfirmed"),
                            isNew = userData.optBoolean("isNew"),
                            isActive = userData.optBoolean("isActive"),
                            isAdmin = userData.optBoolean("isAdmin"),
                            subsEndDate = subsEndDateTimestamp
                        )
                        println("ISADMIN: ${user.isAdmin}")

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
                            isAdmin = user.isAdmin
                        )
                        println("Новая сессия для сохранения: ${session}")

                        userRepository.updateUser(user)
                        sessionManager.updateCurrentSession(session)

                        println("Текущая дата тарифа: ${session.subsEndDate}")
                        sessionManager.saveSession(
                            id = 1,
                            user = user,
                            token = session.token,
                            email = session.email,
                            tariff = session.tariff,
                            subsEndDate = session.subsEndDate,
                            isConfirmed = session.isConfirmed,
                            isActive = session.isActive,
                            createdAt = session.createdAt
                        )
                        sessionManager.updateCurrentUser(user)
                    }

                    return@withContext Result.success(true)
                }
            } catch (e: Exception) {
//                Log.d("Error getting files list $e")
                println("Ошибка обновления данных: ${e}")
                Result.failure(e)
            }
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
        val is_confirmed = when {
            data.optBoolean("isConfirmed") -> data.optBoolean("isConfirmed")
            else -> false
        }

        val is_new = when {
            data.optBoolean("isNew") -> data.optBoolean("isNew")
            else -> false
        }

        val subsEndDate: String  = when {
            data.has("subsEndDate") && data.optString("subsEndDate").isNotBlank() -> data.optString("subsEndDate")
            else -> ""
        }
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val date: Date = format.parse(subsEndDate)!!
        val subsEndDateTimestamp: Long = date.time
        val is_admin = when {
            data.optBoolean("isAdmin") -> data.optBoolean("isAdmin")
            else -> false
        }


        return AuthData(
            id = data.optLong("id", data.optLong("id", 1)),
            email = email,
            isConfirmed = data.optBoolean("isConfirmed"),
            isNew = data.optBoolean("isNew"),
            isActive = data.optBoolean(("isActive")),
            token = token,
            tariff = data.optString(("tariff")),
            subsEndDate = subsEndDateTimestamp,
            isAdmin = data.optBoolean("isAdmin")
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

//private fun JSONObject.optLong(name: String, fallback: String): Long {
//    return TODO("Предоставить возвращаемое значение")
//}
