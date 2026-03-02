package ru.acestar.vpn.ui

import android.content.Context

object VpnSettingsPreferences {
    private const val PREFS_NAME = "vpn_settings"
    private const val KEY_PROTOCOL_MODE = "protocol_mode"
    private const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"

    const val PROTOCOL_AUTO = "auto"
    const val PROTOCOL_UDP = "udp"
    const val PROTOCOL_TCP = "tcp"

    fun getProtocolMode(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PROTOCOL_MODE, PROTOCOL_AUTO)
            ?: PROTOCOL_AUTO
    }

    fun saveProtocolMode(context: Context, mode: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROTOCOL_MODE, mode)
            .apply()
    }

    fun isNotificationsEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_NOTIFICATIONS_ENABLED, true)
    }

    fun setNotificationsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_NOTIFICATIONS_ENABLED, enabled)
            .apply()
    }
}
