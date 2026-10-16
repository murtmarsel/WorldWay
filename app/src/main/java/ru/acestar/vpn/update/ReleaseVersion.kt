package ru.acestar.vpn.update

/** The release must declare the actual Android versionCode, never a guessed semver number. */
object ReleaseVersion {
    fun code(releaseName: String): Int = Regex("\\((\\d+)\\)").find(releaseName)
        ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 } ?: 0
}
