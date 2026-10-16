package ru.acestar.vpn.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import android.content.pm.PackageManager
import java.security.MessageDigest
import ru.acestar.vpn.data.model.UpdateInfo
import java.io.File

/**
 * Управляет скачиванием APK через системный DownloadManager
 * и запуском установки через FileProvider.
 */
class UpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "UpdateManager"
        private const val APK_FILE_NAME = "update.apk"
    }

    private val authority = "${context.packageName}.update.fileprovider"

    /**
     * Скачивает APK в cache/updates и возвращает File при успехе.
     * Использует DownloadManager — переживает закрытие приложения.
     */
    suspend fun downloadApk(info: UpdateInfo): Result<File> = withContext(Dispatchers.IO) {
        try {
            require(info.apkUrl.startsWith("https://github.com/murtmarsel/WorldWay/releases/download/")) { "Недопустимый адрес обновления" }
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates").apply { mkdirs() }
            val fileName = "update-${info.versionCode}-${System.currentTimeMillis()}.apk"
            val target = File(dir, fileName)
            if (target.exists()) target.delete()

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(info.apkUrl)).apply {
                setTitle("Обновление приложения")
                setDescription("Загрузка версии ${info.versionName}")
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                // Качаем во внешний кэш приложения — доступно FileProvider
                setDestinationInExternalFilesDir(
                    context, Environment.DIRECTORY_DOWNLOADS, "updates/$fileName"
                )
                setMimeType("application/vnd.android.package-archive")
            }

            val downloadId = dm.enqueue(request)
            try {
                val resultFile = withTimeout(20 * 60 * 1000L) { awaitDownload(dm, downloadId) }
                    ?: throw Exception("Download failed")
                validateApk(resultFile, info.versionCode)
                Result.success(resultFile)
            } catch (e: Exception) {
                dm.remove(downloadId)
                throw e
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "downloadApk failed", e)
            Result.failure(e)
        }
    }

    /** Блокирующее ожидание завершения скачивания через poll статуса */
    private suspend fun awaitDownload(dm: DownloadManager, id: Long): File? =
        withContext(Dispatchers.IO) {
            while (true) {
                val query = DownloadManager.Query().setFilterById(id)
                dm.query(query).use { cursor ->
                    if (cursor != null && cursor.moveToFirst()) {
                        val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        when (cursor.getInt(statusIdx)) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                val uriIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                                val localUri = cursor.getString(uriIdx)
                                return@withContext localUri?.let { File(Uri.parse(it).path!!) }
                            }
                            DownloadManager.STATUS_FAILED -> return@withContext null
                        }
                    }
                }
                delay(500)
            }
            @Suppress("UNREACHABLE_CODE") null
        }

    /**
     * Запускает системный установщик APK.
     * Требует разрешения REQUEST_INSTALL_PACKAGES + "Установка из неизвестных источников".
     */
    fun installApk(apkFile: File) {
        try {
            validateApk(apkFile)
            val uri = FileProvider.getUriForFile(context, authority, apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "installApk failed", e)
        }
    }

    private fun validateApk(file: File, expectedVersion: Int? = null) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val candidate = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: error("Повреждённый APK")
        val installed = pm.getPackageInfo(context.packageName, flags)
        require(candidate.packageName == context.packageName) { "APK другого приложения" }
        val code = if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong()
        val current = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        require(code > current && (expectedVersion == null || code == expectedVersion.toLong())) { "Номер версии APK не совпал с релизом" }
        fun fingerprints(info: android.content.pm.PackageInfo): Set<String> {
            val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            return signatures.orEmpty().map { signature ->
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
            }.toSet()
        }
        val signer = fingerprints(installed)
        require(signer.isNotEmpty() && signer == fingerprints(candidate)) { "Подпись обновления не совпала" }
    }

    /**
     * Проверяет может ли приложение устанавливать APK (Android 8+).
     */
    fun canInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else true
    }

    /** Intent для запроса разрешения на установку из неизвестных источников */
    fun unknownSourcesSettingsIntent(): Intent {
        return Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }
}
