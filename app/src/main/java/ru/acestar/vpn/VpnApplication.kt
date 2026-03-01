package ru.acestar.vpn

import de.blinkt.openvpn.core.ICSOpenVPNApplication
import ru.acestar.vpn.data.database.AppDatabase
import ru.acestar.vpn.ui.MainActivity

class VpnApplication : ICSOpenVPNApplication() {
    val database: AppDatabase by lazy { AppDatabase.getDatabase(this) }

    override fun getMainClass(): Class<*> = MainActivity::class.java
}
