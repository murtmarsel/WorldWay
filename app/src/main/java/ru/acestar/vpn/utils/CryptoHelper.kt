// utils/CryptoHelper.kt
package ru.acestar.vpn.utils

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
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

    fun encryptData(plainText: String, token: String): String {
        return try {
            val plainBytes = plainText.toByteArray(Charsets.UTF_8)

            // Генерируем случайный IV (Initialization Vector) - 16 байт для AES
            val iv = ByteArray(16)
            SecureRandom().nextBytes(iv)

            val key = generateKeyFromToken(token)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))

            // Шифруем данные
            val encrypted = cipher.doFinal(plainBytes)

            // Объединяем IV + зашифрованные данные
            val combined = ByteArray(iv.size + encrypted.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)

            // Кодируем в Base64
            Base64.encodeToString(combined, Base64.DEFAULT)
        } catch (e: Exception) {
            e.printStackTrace()
            throw Exception("Encryption failed: ${e.message}")
        }
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
    fun decryptDataOld(encryptedBase64: String, token: String): String {
        return try {
            println("========== DECRYPT DEBUG START ==========")

            // Шаг 1: Проверка входных параметров
            println("Step 1: Checking input parameters")
            println("  - encryptedBase64 length: ${encryptedBase64.length}")
            println("  - encryptedBase64 preview: ${encryptedBase64.take(50)}...")
            println("  - token length: ${token.length}")
            println("  - token preview: ${token.take(20)}...")
            println("  - token is not blank: ${token.isNotBlank()}")

            if (encryptedBase64.isBlank()) {
                throw IllegalArgumentException("encryptedBase64 is blank")
            }
            if (token.isBlank()) {
                throw IllegalArgumentException("token is blank")
            }

            // Шаг 2: Base64 декодирование
            println("\nStep 2: Base64 decoding")
            val encryptedData = try {
                Base64.decode(encryptedBase64, Base64.DEFAULT).also {
                    println("  - Decoded successfully")
                    println("  - Decoded data length: ${it.size} bytes")
                    println("  - First 20 bytes (hex): ${it.take(20).joinToString(" ") { "%02x".format(it) }}")
                }
            } catch (e: Exception) {
                println("  - ERROR: ${e.message}")
                throw Exception("Base64 decode failed: ${e.message}")
            }

            // Шаг 3: Проверка длины данных
            println("\nStep 3: Data length validation")
            println("  - Minimum required length: 16 bytes (IV)")
            println("  - Actual length: ${encryptedData.size} bytes")

            if (encryptedData.size < 16) {
                throw Exception("Data too short: ${encryptedData.size} bytes (minimum 16 bytes for IV)")
            }

            // Шаг 4: Извлечение IV
            println("\nStep 4: Extracting IV (first 16 bytes)")
            val iv = encryptedData.copyOfRange(0, 16)
            println("  - IV length: ${iv.size} bytes")
            println("  - IV (hex): ${iv.joinToString(" ") { "%02x".format(it) }}")

            // Шаг 5: Извлечение зашифрованных данных
            println("\nStep 5: Extracting encrypted data (remaining bytes)")
            val encrypted = encryptedData.copyOfRange(16, encryptedData.size)
            println("  - Encrypted data length: ${encrypted.size} bytes")
            println("  - Is multiple of 16: ${encrypted.size % 16 == 0}")
            if (encrypted.size % 16 != 0) {
                println("  - WARNING: Encrypted data length is not a multiple of 16!")
            }
            println("  - First 20 bytes (hex): ${encrypted.take(20).joinToString(" ") { "%02x".format(it) }}")

            // Шаг 6: Генерация ключа из токена
            println("\nStep 6: Generating key from token")
            val key = try {
                generateKeyFromToken(token).also {
                    println("  - Key generated successfully")
                    println("  - Key algorithm: ${it.algorithm}")
                    println("  - Key format: ${it.format}")
                    println("  - Key length: ${it.encoded.size} bytes")
                    println("  - Key bytes (first 16): ${it.encoded.take(16).joinToString(" ") { "%02x".format(it) }}")
                }
            } catch (e: Exception) {
                println("  - ERROR: ${e.message}")
                throw Exception("Key generation failed: ${e.message}")
            }

            // Шаг 7: Инициализация шифра
            println("\nStep 7: Initializing cipher")
            println("  - Transformation: $TRANSFORMATION")
            val cipher = try {
                Cipher.getInstance(TRANSFORMATION).also {
                    println("  - Cipher instance created successfully")
                }
            } catch (e: Exception) {
                println("  - ERROR: ${e.message}")
                throw Exception("Cipher initialization failed: ${e.message}")
            }

            // Шаг 8: Инициализация режима дешифровки
            println("\nStep 8: Initializing decrypt mode")
            try {
                cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
                println("  - Decrypt mode initialized successfully")
                println("  - Cipher block size: ${cipher.blockSize} bytes")
            } catch (e: Exception) {
                println("  - ERROR: ${e.message}")
                println("  - Possible causes:")
                println("    1. Invalid key length (AES requires 16, 24, or 32 bytes)")
                println("    2. Invalid IV length (AES requires 16 bytes)")
                println("    3. Incompatible key algorithm")
                throw Exception("Cipher init failed: ${e.message}")
            }

            // Шаг 9: Дешифровка
            println("\nStep 9: Performing decryption")
            val decryptedData = try {
                cipher.doFinal(encrypted).also {
                    println("  - Decryption completed successfully")
                    println("  - Decrypted data length: ${it.size} bytes")
                    println("  - First 20 bytes (hex): ${it.take(20).joinToString(" ") { "%02x".format(it) }}")
                    println("  - First 20 chars (text): ${it.take(20).map { if (it.toInt() in 32..126) it.toChar() else '?' }.joinToString("")}")
                }
            } catch (e: Exception) {
                println("  - ERROR: ${e.message}")
                println("  - Exception type: ${e.javaClass.simpleName}")
                e.printStackTrace()
                throw Exception("Decryption failed: ${e.message}")
            }

            // Шаг 10: Преобразование в строку
            println("\nStep 10: Converting to UTF-8 string")
            val result = try {
                String(decryptedData, Charsets.UTF_8).also {
                    println("  - Conversion successful")
                    println("  - Result length: ${it.length} chars")
                    println("  - Result preview: ${it.take(100)}")
                }
            } catch (e: Exception) {
                println("  - ERROR: ${e.message}")
                throw Exception("String conversion failed: ${e.message}")
            }

            println("\n========== DECRYPT DEBUG END ==========")
            result

        } catch (e: Exception) {
            println("\n========== DECRYPT ERROR ==========")
            println("Error type: ${e.javaClass.simpleName}")
            println("Error message: ${e.message}")
            println("Stack trace:")
            e.printStackTrace()
            println("=====================================")
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