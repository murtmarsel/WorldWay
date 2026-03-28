package ru.acestar.vpn.utils

import java.security.MessageDigest

/**
 * Одностороннее хеширование пароля перед отправкой на сервер.
 * Формат: SHA-256(lowercase(email) + ":" + password) в hex.
 * Сервер должен проверять пароль тем же алгоритмом.
 */
object PasswordHasher {

    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Хеш для полей login / reg (поле password в JSON). */
    fun hashForServer(password: String, email: String): String {
        val normalizedEmail = email.lowercase().trim()
        return sha256Hex("$normalizedEmail:$password")
    }
}
