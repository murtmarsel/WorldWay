package ru.acestar.vpn.utils

object FavoriteConfigLabel {
    private val ipv4 = Regex("(?<![\\d.])((?:\\d{1,3}\\.){3}\\d{1,3})(?![\\d.])")
    fun format(host: String, content: String): String {
        val endpoint = content.lineSequence().map { it.trim() }.firstOrNull {
            it.startsWith("remote ", true) || it.startsWith("remote\t", true) || it.startsWith("Endpoint", true)
        }.orEmpty()
        val address = sequenceOf(host, endpoint).flatMap { ipv4.findAll(it).map { match -> match.groupValues[1] } }
            .firstOrNull { ip -> ip.split('.').all { it.toInt() in 0..255 } }
        val short = address?.substringBefore('.') ?: host.trim().takeIf { it.isNotBlank() }
            ?.removePrefix("[")?.substringBefore(':')?.substringBefore('.')?.take(12) ?: "VPN"
        val transport = content.lineSequence().map { it.trim().lowercase().split(Regex("\\s+")) }
            .firstOrNull { it.size > 1 && it[0] == "proto" }?.get(1).orEmpty()
        val protocol = when {
            transport.startsWith("tcp") -> "TCP"
            transport.startsWith("udp") -> "UDP"
            else -> "AUTO"
        }
        return "$short $protocol"
    }
}
