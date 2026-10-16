package ru.acestar.vpn.update

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.acestar.vpn.data.model.UpdateInfo

/**
 * Обрабатывает нажатия кнопок в уведомлении об обновлении.
 * ACTION_UPDATE_NOW — скачать и установить
 * ACTION_POSTPONE   — открыть приложение для выбора времени
 */
class UpdateNotificationReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_UPDATE_NOW = "ru.acestar.vpn.UPDATE_NOW"
        const val ACTION_POSTPONE   = "ru.acestar.vpn.UPDATE_POSTPONE"
        const val EXTRA_VERSION_NAME = "version_name"
        const val EXTRA_VERSION_CODE = "version_code"
        const val EXTRA_APK_URL = "apk_url"
        const val EXTRA_APK_SIZE = "apk_size"
        const val EXTRA_NOTES = "notes"
        const val NOTIFICATION_ID = 2002
    }

    override fun onReceive(context: Context, intent: Intent) {
        val info = UpdateInfo(
            versionCode = intent.getIntExtra(EXTRA_VERSION_CODE, 0),
            versionName = intent.getStringExtra(EXTRA_VERSION_NAME) ?: "",
            releaseNotes = intent.getStringExtra(EXTRA_NOTES) ?: "",
            apkUrl = intent.getStringExtra(EXTRA_APK_URL) ?: "",
            apkSize = intent.getLongExtra(EXTRA_APK_SIZE, 0L)
        )

        // Закрываем уведомление
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)

        when (intent.action) {
            ACTION_UPDATE_NOW -> {
                // Скачиваем и устанавливаем в фоне
                val manager = UpdateManager(context)
                CoroutineScope(Dispatchers.IO).launch {
                    manager.downloadApk(info).onSuccess { file ->
                        manager.installApk(file)
                    }
                }
            }
            ACTION_POSTPONE -> {
                // Открываем приложение с флагом выбора времени обновления
                val openIntent = context.packageManager
                    .getLaunchIntentForPackage(context.packageName)?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra("open_update_schedule", true)
                        putExtra(EXTRA_VERSION_CODE, info.versionCode)
                        putExtra(EXTRA_VERSION_NAME, info.versionName)
                        putExtra(EXTRA_APK_URL, info.apkUrl)
                        putExtra(EXTRA_APK_SIZE, info.apkSize)
                        putExtra(EXTRA_NOTES, info.releaseNotes)
                    }
                openIntent?.let { context.startActivity(it) }
            }
        }
    }
}

