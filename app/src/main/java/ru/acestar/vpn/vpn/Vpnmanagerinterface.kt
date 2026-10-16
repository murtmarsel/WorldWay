package ru.acestar.vpn.vpn

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow

/**
 * Общий интерфейс для OpenVpnManager и WireGuardManager.
 * Позволяет ViewModel работать с любым менеджером без if/else на каждый поток.
 */
interface VpnManagerInterface {
    val connectionState: StateFlow<VpnConnectionState>
    val statusMessage:   StateFlow<String>
    val byteCountIn:     StateFlow<Long>
    val byteCountOut:    StateFlow<Long>
    val isPaused:        StateFlow<Boolean>

    fun connect(activity: Activity)
    fun disconnect()
    fun isConnected(): Boolean
}