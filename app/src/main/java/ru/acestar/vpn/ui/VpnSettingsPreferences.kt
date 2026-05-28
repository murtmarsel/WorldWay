package ru.acestar.vpn.ui

import android.content.Context

object VpnSettingsPreferences {
    private const val PREFS_NAME = "vpn_settings"
    private const val KEY_PROTOCOL_MODE = "protocol_mode"
    private const val KEY_TOAST_NOTIFICATIONS_ENABLED = "toast_notifications_enabled"
    private const val KEY_IS_SERVER_CONFIG_SOURCE = "is_server_config_source"
    private const val KEY_SELECTED_CONFIG_ID = "selected_config_id"

    const val PROTOCOL_AUTO = "auto"
    const val PROTOCOL_UDP = "udp"
    const val PROTOCOL_TCP = "tcp"

    fun getIsServerConfig(context: Context): Boolean{
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_IS_SERVER_CONFIG_SOURCE, false)
    }
    fun saveIsServerConfig(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_IS_SERVER_CONFIG_SOURCE, enabled)
            .apply()
    }

    fun getSelectedConfigId(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_SELECTED_CONFIG_ID, -1L)
    }
    fun saveSelectedConfigId(context: Context, id: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_SELECTED_CONFIG_ID, id)
            .apply()
    }

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

    fun isToastNotificationsEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_TOAST_NOTIFICATIONS_ENABLED, true)
    }

    fun setToastNotificationsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_TOAST_NOTIFICATIONS_ENABLED, enabled)
            .apply()
    }
}
