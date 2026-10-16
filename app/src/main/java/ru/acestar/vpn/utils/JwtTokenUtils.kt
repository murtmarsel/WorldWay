package ru.acestar.vpn.utils

import android.util.Base64
import org.json.JSONObject

/**
 * Извлечение exp из JWT токена.
 *
 * Ожидается формат: header.payload.signature (Base64URL).
 * Возвращает время в миллисекундах (epoch ms) или null, если exp нет/не распарсилось.
 */
object JwtTokenUtils {

    fun extractExpiryMillis(token: String): Long? {
        val parts = token.split('.')
        if (parts.size < 2) return null

        val payloadPart = parts[1].trim()
        if (payloadPart.isEmpty()) return null

        val padded = payloadPart + "=".repeat((4 - (payloadPart.length % 4)) % 4)

        val payloadBytes = try {
            Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP)
        } catch (_: Exception) {
            return null
        }

        val payloadJson = try {
            JSONObject(String(payloadBytes, Charsets.UTF_8))
        } catch (_: Exception) {
            return null
        }

        val expAny = payloadJson.opt("exp")
        val expSecondsOrMillis = when (expAny) {
            is Number -> expAny.toLong()
            is String -> expAny.toLongOrNull()
            else -> null
        } ?: return null

        if (expSecondsOrMillis <= 0L) return null

        // Если exp похож на ms (обычно >= 1e12) — оставляем, иначе умножаем на 1000 (секунды).
        return if (expSecondsOrMillis >= 1_000_000_000_000L) {
            expSecondsOrMillis
        } else {
            expSecondsOrMillis * 1000L
        }
    }
}

