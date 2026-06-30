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
 * versionCode извлекается:
 *   - из имени релиза если оно вида "123" или содержит "(123)"
 *   - иначе вычисляется из версии X.Y.Z как X*10000 + Y*100 + Z
 * APK должен быть прикреплён как ассет релиза с расширением .apk
 */
class UpdateApiService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    companion object {
        // TODO: замените на ваш репозиторий
        private const val GITHUB_OWNER = "YOUR_GITHUB_OWNER"
        private const val GITHUB_REPO  = "YOUR_GITHUB_REPO"
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

                val versionCode = extractVersionCode(releaseName, tagName)

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

    /**
     * Извлекает versionCode.
     * Приоритет: число в скобках имени релиза "(123)" → версия X.Y.Z в число.
     */
    private fun extractVersionCode(releaseName: String, tag: String): Int {
        // Формат "1.0.5 (12)" — берём число в скобках
        val inParens = Regex("""\((\d+)\)""").find(releaseName)?.groupValues?.get(1)
        if (inParens != null) return inParens.toIntOrNull() ?: 0

        // Иначе вычисляем из X.Y.Z
        val parts = tag.split(".").mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 10000 + parts[1] * 100 + parts[2]
            2 -> parts[0] * 10000 + parts[1] * 100
            1 -> parts[0] * 10000
            else -> 0
        }
    }
}
