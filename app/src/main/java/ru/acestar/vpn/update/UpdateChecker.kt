package ru.acestar.vpn.update

import android.content.Context
import android.content.pm.PackageManager
import ru.acestar.vpn.api.UpdateApiService
import ru.acestar.vpn.data.model.UpdateInfo
import ru.acestar.vpn.ui.VpnSettingsPreferences

/**
 * Координатор проверки обновлений.
 * Решает нужно ли обновление, учитывает настройку автообновления,
 * отложенную версию и запланированное время.
 */
class UpdateChecker(private val context: Context) {

    private val api = UpdateApiService()

    /**
     * Текущий versionCode приложения.
     */
    fun currentVersionCode(): Int {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION") info.versionCode
            }
        } catch (e: PackageManager.NameNotFoundException) {
            0
        }
    }

    /**
     * Проверяет доступность обновления.
     * @return UpdateInfo если есть новая версия, иначе null
     */
    suspend fun checkForUpdate(): UpdateInfo? {
        if (!VpnSettingsPreferences.isAutoUpdateEnabled(context)) return null

        val result = api.fetchLatestRelease()
        val info = result.getOrNull() ?: return null

        // Новее текущей?
        if (info.versionCode <= currentVersionCode()) return null

        return info
    }

    /**
     * Проверяет наступило ли запланированное время установки.
     */
    fun isScheduledTimeReached(): Boolean {
        val scheduled = VpnSettingsPreferences.getUpdateScheduledAt(context)
        return scheduled in 1..System.currentTimeMillis()
    }

    fun hasScheduledUpdate(): Boolean {
        return VpnSettingsPreferences.getUpdateScheduledAt(context) > 0L
    }

    fun scheduleUpdate(timeMs: Long, versionCode: Int) {
        VpnSettingsPreferences.setUpdateScheduledAt(context, timeMs)
        VpnSettingsPreferences.setPostponedVersion(context, versionCode)
    }

    fun clearSchedule() {
        VpnSettingsPreferences.setUpdateScheduledAt(context, 0L)
    }
}
