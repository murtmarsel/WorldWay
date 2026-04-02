// api/ClientFilesApiService.kt
package ru.acestar.vpn.api

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import ru.acestar.vpn.data.model.ClientFile
import ru.acestar.vpn.data.model.ConfirmEmail
import ru.acestar.vpn.data.model.DownloadedFile
import ru.acestar.vpn.data.model.admin.UserConfigFile
import ru.acestar.vpn.data.repository.SessionManager
import ru.acestar.vpn.utils.CryptoHelper
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ClientFilesApiService(private val sessionManager: SessionManager) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private suspend fun getToken(): String? {
        val token = sessionManager.getAccessToken()
        return token
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val baseUrl = "http://94.249.239.35:5001" // Замените на ваш URL

    // Токен авторизации
    private var authToken: String? = null

    fun setAuthToken(token: String?) {
        authToken = token
        Log.d("ClientFilesApiService", "Token set: ${token?.take(10)}...")
    }

    /**
     * Шаг 1: Получение списка файлов для клиента
     */
    suspend fun getClientFilesList(clientName: Long): Result<List<UserConfigFile>> =
        withContext(Dispatchers.IO) {
            try {
                val token = getToken()
                    ?: return@withContext Result.failure(
                        Exception("Нет токена пользователя.")
                    )

                Log.d("AuthApiService", "📤 Getting configs list for user id: $clientName")

                val request = Request.Builder()
                    .url("$baseUrl/api/users/$clientName/configs")
                    .get()
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .build()

                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    Log.d("AuthApiService", "📥 Response code: ${response.code}")

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
                        Log.d("AuthApiService", "🔓 Decrypted data: $decryptedJson")

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
                Log.e("AuthApiService", "Error getting files list", e)
                Result.failure(e)
            }
        }

    /**
     * Парсинг JSON в ClientFile
     */
//    private fun parseClientFile(json: JSONObject): ClientFile? {
//        return try {
//            ClientFile(
//                name = json.optString("name", ""),
//                path = json.optString("path", ""),
//                size = json.optLong("size", 0),
//                modified = json.optDouble("modified", 0.0),
//                type = json.optString("type", "unknown"),
//                category = json.optString("category", null),
//                protocol = json.optString("protocol", null),
//                config = json.optString("config", null)
//            )
//        } catch (e: Exception) {
//            Log.e("ClientFilesApiService", "Error parsing client file", e)
//            null
//        }
//    }

    /**
     * Шаг 2: Скачивание конкретного файла
     */
//    suspend fun downloadFile(clientName: String, file: ClientFile): Result<DownloadedFile> =
//        withContext(Dispatchers.IO) {
//            try {
//                val token = authToken ?: return@withContext Result.failure(
//                    Exception("No auth token")
//                )
//
//                Log.d("ClientFilesApiService", "📥 Downloading file: ${file.name}")
//
//                // URL-кодируем имя файла
//                val encodedFileName = URLEncoder.encode(file.name, "UTF-8")
//
//                val jsonBody = JSONObject().apply {
//                    put("email", ConfirmEmail.email)
//                    put("isConfirmed", ConfirmEmail.isConfirmed)
//                }
//
//                val request = Request.Builder()
//                    .url("$baseUrl/api/v1/genConfigs")
//                    .post(jsonBody.toString().toRequestBody(jsonMediaType))
//                    .addHeader("Authorization", "Bearer $token")
//                    .build()
//
//                client.newCall(request).execute().use { response ->
//                    val responseBody = response.body?.string()
//
//                    if (!response.isSuccessful) {
//                        return@withContext Result.failure(
//                            Exception("HTTP ${response.code}: ${response.message}")
//                        )
//                    }
//
//                    if (responseBody.isNullOrEmpty()) {
//                        return@withContext Result.failure(Exception("Empty response"))
//                    }
//
//                    // Парсим JSON ответ
//                    val jsonResponse = JSONObject(responseBody)
//
//                    val success = jsonResponse.optBoolean("success", false)
//                    if (!success) {
//                        val error = jsonResponse.optString("error", "Download failed")
//                        return@withContext Result.failure(Exception(error))
//                    }
//
//                    val dataJson = jsonResponse.optJSONObject("data")
//                    if (dataJson == null) {
//                        return@withContext Result.failure(Exception("No data in response"))
//                    }
//
//                    val fileName = dataJson.optString("name", "")
//                    val fileContentBase64 = dataJson.optString("content", "")
//                    val fileSize = dataJson.optLong("size", 0)
//                    val encrypted = dataJson.optBoolean("encrypted", false)
//
//                    if (fileName.isEmpty() || fileContentBase64.isEmpty()) {
//                        return@withContext Result.failure(Exception("Invalid file data"))
//                    }
//
//                    // Дешифруем содержимое файла
//                    val decryptedContent = if (encrypted) {
//                        CryptoHelper.decryptFileContent(fileContentBase64, token)
//                    } else {
//                        Base64.decode(fileContentBase64, Base64.DEFAULT)
//                    }
//
//                    // Кодируем обратно в Base64 для хранения в БД
//                    val contentForDb = Base64.encodeToString(decryptedContent, Base64.DEFAULT)
//
//                    val downloadedFile = DownloadedFile(
//                        client_name = clientName,
//                        file_name = fileName,
//                        file_path = file.path,
//                        file_size = fileSize,
//                        file_type = file.type,
//                        downloaded_at = System.currentTimeMillis(),
//                        file_content = contentForDb,
//                        is_imported = false
//                    )
//
//                    Log.d("ClientFilesApiService", "✅ File downloaded: $fileName")
//                    Result.success(downloadedFile)
//                }
//            } catch (e: Exception) {
//                Log.e("ClientFilesApiService", "Error downloading file", e)
//                Result.failure(e)
//            }
//        }

    /**
     * Шаг 3: Полный процесс - получить список и скачать все файлы с прогрессом
     */
//    suspend fun downloadAllClientFiles(
//        clientName: String,
//        onProgress: (current: Int, total: Int, fileName: String) -> Unit
//    ): Result<List<DownloadedFile>> = withContext(Dispatchers.IO) {
//        try {
//            // 1. Получаем список файлов
//            val filesResult = getClientFilesList(clientName)
//
//            if (filesResult.isFailure) {
//                return@withContext Result.failure(filesResult.exceptionOrNull()!!)
//            }
//
//            val files = filesResult.getOrNull() ?: emptyList()
//            val downloadedFiles = mutableListOf<DownloadedFile>()
//
//            Log.d("ClientFilesApiService", "Found ${files.size} files to download")
//
//            // 2. Скачиваем каждый файл
//            for ((index, file) in files.withIndex()) {
//                onProgress(index, files.size, file.name)
//
//                val downloadResult = downloadFile(clientName, file)
//
//                if (downloadResult.isSuccess) {
//                    downloadResult.getOrNull()?.let { downloadedFiles.add(it) }
//                } else {
//                    Log.e("ClientFilesApiService", "Failed to download ${file.name}: ${downloadResult.exceptionOrNull()?.message}")
//                }
//            }
//
//            onProgress(files.size, files.size, "Complete")
//            Result.success(downloadedFiles)
//
//        } catch (e: Exception) {
//            Log.e("ClientFilesApiService", "Error in download process", e)
//            Result.failure(e)
//        }
//    }

    /**
     * Поток для отслеживания прогресса
     */
//    fun downloadAllClientFilesFlow(clientName: String): Flow<DownloadProgress> = flow {
//        try {
//            emit(DownloadProgress.Started)
//
//            val filesResult = getClientFilesList(clientName)
//
//            if (filesResult.isFailure) {
//                emit(DownloadProgress.Error(filesResult.exceptionOrNull()?.message ?: "Unknown error"))
//                return@flow
//            }
//
//            val files = filesResult.getOrNull() ?: emptyList()
//            emit(DownloadProgress.FilesListReceived(files.size))
//
//            val downloadedFiles = mutableListOf<DownloadedFile>()
//
//            for ((index, file) in files.withIndex()) {
//                emit(DownloadProgress.Downloading(index, files.size, file.name))
//
//                val downloadResult = downloadFile(clientName, file)
//
//                if (downloadResult.isSuccess) {
//                    downloadResult.getOrNull()?.let {
//                        downloadedFiles.add(it)
//                        emit(DownloadProgress.FileDownloaded(index + 1, files.size, file.name))
//                    }
//                } else {
//                    emit(DownloadProgress.FileFailed(file.name, downloadResult.exceptionOrNull()?.message ?: "Failed"))
//                }
//            }
//
//            emit(DownloadProgress.Completed(downloadedFiles))
//
//        } catch (e: Exception) {
//            emit(DownloadProgress.Error(e.message ?: "Unknown error"))
//        }
//    }

    sealed class DownloadProgress {
        object Started : DownloadProgress()
        data class FilesListReceived(val totalFiles: Int) : DownloadProgress()
        data class Downloading(val current: Int, val total: Int, val fileName: String) : DownloadProgress()
        data class FileDownloaded(val current: Int, val total: Int, val fileName: String) : DownloadProgress()
        data class FileFailed(val fileName: String, val error: String) : DownloadProgress()
        data class Completed(val files: List<DownloadedFile>) : DownloadProgress()
        data class Error(val message: String) : DownloadProgress()
    }
}