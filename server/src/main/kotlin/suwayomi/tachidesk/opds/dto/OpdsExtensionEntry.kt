package suwayomi.tachidesk.opds.dto

data class OpdsExtensionEntry(
    val pkgName: String,
    val name: String,
    val versionName: String,
    val lang: String,
    val isNsfw: Boolean,
    val isInstalled: Boolean,
    val hasUpdate: Boolean,
    val isObsolete: Boolean,
    val iconUrl: String,
)
