// models/ClientFileModels.kt
package ru.acestar.vpn.data.model

import kotlinx.serialization.Serializable
import java.util.Date

@Serializable
data class ClientFile(
    val name: String,
    val path: String,
    val size: Long,
    val modified: Double,
    val type: String,
    val category: String? = null,
    val protocol: String? = null,
    val config: String? = null
) {
    val modifiedDate: Date
        get() = Date((modified * 1000).toLong())

    val sizeFormatted: String
        get() {
            val kb = size / 1024.0
            val mb = kb / 1024.0
            return when {
                mb >= 1 -> String.format("%.2f MB", mb)
                kb >= 1 -> String.format("%.2f KB", kb)
                else -> "$size B"
            }
        }
}

@Serializable
data class ClientFilesResponse(
    val success: Boolean,
    val data: String? = null,
    val encrypted: Boolean = false,
    val error: String? = null
)

@Serializable
data class ClientFilesData(
    val client: String,
    val file_count: Int,
    val files: List<ClientFile>,
    val server_ip: String,
    val timestamp: String
)

@Serializable
data class DownloadFileResponse(
    val success: Boolean,
    val data: DownloadFileData? = null,
    val error: String? = null
)

@Serializable
data class DownloadFileData(
    val name: String,
    val content: String,
    val size: Long,
    val encrypted: Boolean
)

@Serializable
data class DownloadedFile(
    val id: Long = 0,
    val client_name: String,
    val file_name: String,
    val file_path: String,
    val file_size: Long,
    val file_type: String,
    val downloaded_at: Long,
    val file_content: String, // base64 encoded
    var is_imported: Boolean = false
)