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
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val target = File(dir, APK_FILE_NAME)
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
                    context, Environment.DIRECTORY_DOWNLOADS, "updates/$APK_FILE_NAME"
                )
                setMimeType("application/vnd.android.package-archive")
            }

            val downloadId = dm.enqueue(request)
            val resultFile = awaitDownload(dm, downloadId)
            if (resultFile != null) Result.success(resultFile)
            else Result.failure(Exception("Download failed"))
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
                Thread.sleep(500)
            }
            @Suppress("UNREACHABLE_CODE") null
        }

    /**
     * Запускает системный установщик APK.
     * Требует разрешения REQUEST_INSTALL_PACKAGES + "Установка из неизвестных источников".
     */
    fun installApk(apkFile: File) {
        try {
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
