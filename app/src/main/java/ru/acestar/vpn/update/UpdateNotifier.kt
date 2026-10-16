package ru.acestar.vpn.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ru.acestar.vpn.R
import ru.acestar.vpn.data.model.UpdateInfo

/**
 * Показывает системное уведомление об обновлении с кнопками "Позже" и "Обновить".
 */
object UpdateNotifier {

    private const val CHANNEL_ID = "vpn_update"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.update_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = context.getString(R.string.update_channel_desc) }
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    fun showUpdateNotification(context: Context, info: UpdateInfo) {
        createChannel(context)

        val updateIntent = Intent(context, UpdateNotificationReceiver::class.java).apply {
            action = UpdateNotificationReceiver.ACTION_UPDATE_NOW
            putExtra(UpdateNotificationReceiver.EXTRA_VERSION_CODE, info.versionCode)
            putExtra(UpdateNotificationReceiver.EXTRA_VERSION_NAME, info.versionName)
            putExtra(UpdateNotificationReceiver.EXTRA_APK_URL, info.apkUrl)
            putExtra(UpdateNotificationReceiver.EXTRA_APK_SIZE, info.apkSize)
            putExtra(UpdateNotificationReceiver.EXTRA_NOTES, info.releaseNotes)
        }
        val postponeIntent = Intent(context, UpdateNotificationReceiver::class.java).apply {
            action = UpdateNotificationReceiver.ACTION_POSTPONE
            putExtra(UpdateNotificationReceiver.EXTRA_VERSION_CODE, info.versionCode)
            putExtra(UpdateNotificationReceiver.EXTRA_VERSION_NAME, info.versionName)
            putExtra(UpdateNotificationReceiver.EXTRA_APK_URL, info.apkUrl)
            putExtra(UpdateNotificationReceiver.EXTRA_APK_SIZE, info.apkSize)
            putExtra(UpdateNotificationReceiver.EXTRA_NOTES, info.releaseNotes)
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val updatePending = PendingIntent.getBroadcast(context, 1, updateIntent, flags)
        val postponePending = PendingIntent.getBroadcast(context, 2, postponeIntent, flags)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(context.getString(R.string.update_available_title))
            .setContentText(context.getString(R.string.update_available_text, info.versionName))
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                context.getString(R.string.update_available_text, info.versionName) +
                    if (info.releaseNotes.isNotBlank()) "\n\n${info.releaseNotes.take(200)}" else ""
            ))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)
            .addAction(0, context.getString(R.string.update_later), postponePending)
            .addAction(0, context.getString(R.string.update_now), updatePending)
            .build()

        try {
            NotificationManagerCompat.from(context)
                .notify(UpdateNotificationReceiver.NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Нет разрешения POST_NOTIFICATIONS
        }
    }
}
