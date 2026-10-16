package ru.acestar.vpn.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import ru.acestar.vpn.data.model.UpdateInfo
import java.util.concurrent.TimeUnit

/**
 * Проверка обновлений через GitHub Releases API.
 *
 * Тег релиза должен быть в формате семантической версии "X.Y.Z" (можно с префиксом "v").
 * versionCode берётся из имени релиза вида "1.0.6 (7)" и сверяется с APK.
 * APK должен быть прикреплён как ассет релиза с расширением .apk
 */
class UpdateApiService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val GITHUB_OWNER = "murtmarsel"
        private const val GITHUB_REPO  = "WorldWay"
        private const val TAG = "UpdateApiService"
    }

    private val latestReleaseUrl =
        "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    /**
     * Получает информацию о последнем релизе.
     * @return UpdateInfo или null если релизов нет / ошибка сети
     */
    suspend fun fetchLatestRelease(): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(latestReleaseUrl)
                .addHeader("Accept", "application/vnd.github+json")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("GitHub API HTTP ${response.code}")
                    )
                }
                val body = response.body?.string()
                    ?: return@withContext Result.failure(Exception("Пустой ответ GitHub"))

                val json = JSONObject(body)
                val tagName = json.optString("tag_name", "").removePrefix("v").trim()
                val releaseName = json.optString("name", "")
                val notes = json.optString("body", "")

                if (tagName.isBlank()) {
                    return@withContext Result.failure(Exception("Нет тега релиза"))
                }

                // Ищем APK среди ассетов
                val assets = json.optJSONArray("assets")
                var apkUrl = ""
                var apkSize = 0L
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkUrl = asset.optString("browser_download_url", "")
                            apkSize = asset.optLong("size", 0L)
                            break
                        }
                    }
                }

                if (apkUrl.isBlank()) {
                    return@withContext Result.failure(Exception("В релизе нет APK-файла"))
                }

                val versionCode = ru.acestar.vpn.update.ReleaseVersion.code(releaseName)
                if (versionCode <= 0) return@withContext Result.failure(Exception("У релиза не указан versionCode: используйте имя 1.0.6 (7)"))
                val uri = java.net.URI(apkUrl)
                if (uri.scheme != "https" || uri.host != "github.com" ||
                    !uri.path.startsWith("/$GITHUB_OWNER/$GITHUB_REPO/releases/download/")) {
                    return@withContext Result.failure(Exception("Недопустимый адрес APK"))
                }

                Result.success(
                    UpdateInfo(
                        versionCode = versionCode,
                        versionName = tagName,
                        releaseNotes = notes,
                        apkUrl = apkUrl,
                        apkSize = apkSize
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchLatestRelease failed: ${e.message}")
            Result.failure(e)
        }
    }

}
