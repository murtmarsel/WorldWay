package ru.acestar.vpn.data.model

data class Server(
    val id: Int,
    val name: String,
    val country: String,
    val configPath: String? = null,
    val isAvailable: Boolean = true
)
