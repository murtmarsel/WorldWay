package ru.acestar.vpn.data.model

/**
 * Информация об обновлении, полученная из GitHub Releases.
 */
data class UpdateInfo(
    val versionCode: Int,        // извлекается из тега или имени релиза
    val versionName: String,     // tag_name, напр. "1.0.5"
    val releaseNotes: String,    // body релиза
    val apkUrl: String,          // browser_download_url APK-ассета
    val apkSize: Long            // размер APK в байтах
)
