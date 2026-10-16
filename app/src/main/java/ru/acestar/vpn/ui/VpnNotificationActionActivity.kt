package ru.acestar.vpn.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import de.blinkt.openvpn.core.IOpenVPNServiceInternal
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager

/** Handles the library notification's disconnect PendingIntent without confirmation. */
class VpnNotificationActionActivity : Activity() {
    private var bound = false
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { finish() }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            try {
                ProfileManager.setConntectedVpnProfileDisconnected(this@VpnNotificationActionActivity)
                IOpenVPNServiceInternal.Stub.asInterface(binder)?.stopVPN(false)
            } catch (e: Exception) {
                Log.e("VpnNotificationAction", "Failed to disconnect VPN", e)
            } finally {
                finish()
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) { finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action != "de.blinkt.openvpn.DISCONNECT_VPN") {
            finish()
            return
        }
        try {
            bound = bindService(Intent(this, OpenVPNService::class.java).apply {
                action = OpenVPNService.START_SERVICE
            }, connection, Context.BIND_AUTO_CREATE)
            if (!bound) finish()
            else handler.postDelayed(timeout, 10_000)
        } catch (e: Exception) {
            Log.e("VpnNotificationAction", "Failed to bind VPN service", e)
            finish()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        if (bound) unbindService(connection)
        super.onDestroy()
    }
}

