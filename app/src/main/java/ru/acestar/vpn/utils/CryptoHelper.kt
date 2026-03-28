// utils/CryptoHelper.kt
package ru.acestar.vpn.utils

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object CryptoHelper {

    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/CBC/PKCS5Padding"

    fun generateKeyFromToken(token: String): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(token.toByteArray(Charsets.UTF_8))
        val aesKey = keyBytes.copyOf(16)
        return SecretKeySpec(aesKey, ALGORITHM)
    }

    fun decryptData(encryptedBase64: String, token: String): String {
        return try {
            val encryptedData = Base64.decode(encryptedBase64, Base64.DEFAULT)

            // Для CBC режима нужно извлечь IV (первые 16 байт)
            // Если сервер использует Fernet, формат другой
            val iv = encryptedData.copyOfRange(0, 16)
            val encrypted = encryptedData.copyOfRange(16, encryptedData.size)

            val key = generateKeyFromToken(token)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, javax.crypto.spec.IvParameterSpec(iv))

            val decryptedData = cipher.doFinal(encrypted)
            String(decryptedData, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            throw Exception("Decryption failed: ${e.message}")
        }
    }

    fun decryptFileContent(encryptedBase64: String, token: String): ByteArray {
        return try {
            val encryptedData = Base64.decode(encryptedBase64, Base64.DEFAULT)

            val iv = encryptedData.copyOfRange(0, 16)
            val encrypted = encryptedData.copyOfRange(16, encryptedData.size)

            val key = generateKeyFromToken(token)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, javax.crypto.spec.IvParameterSpec(iv))

            cipher.doFinal(encrypted)
        } catch (e: Exception) {
            e.printStackTrace()
            throw Exception("File decryption failed: ${e.message}")
        }
    }
}