package ru.acestar.vpn

import de.blinkt.openvpn.core.ICSOpenVPNApplication
import ru.acestar.vpn.data.database.AppDatabase
import ru.acestar.vpn.ui.MainActivity
import ru.acestar.vpn.ui.ThemePreferences

class VpnApplication : ICSOpenVPNApplication() {
    val database: AppDatabase by lazy { AppDatabase.getDatabase(this) }

    override fun onCreate() {
        super.onCreate()
        ThemePreferences.applyTheme(this)
        ru.acestar.vpn.update.UpdateNotifier.createChannel(this)
    }

    override fun getMainClass(): Class<*> = MainActivity::class.java
}
