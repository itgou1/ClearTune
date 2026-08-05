package com.cleartune.data.local

import java.io.File

class MediaStoreRowMapper {
    fun map(row: MediaStoreRow): LocalAudioSnapshot? {
        val displayName = row.displayName?.trim().orEmpty()
        if (row.id < 0 || displayName.isBlank() || row.sizeBytes < 0 || !isSupported(displayName, row.mimeType)) {
            return null
        }
        val relativeFolder = normalizeFolder(
            row.relativePath ?: row.dataPath?.let { File(it).parent },
        )
        val title = row.title?.trim().takeUnless(String?::isNullOrBlank)
            ?: displayName.substringBeforeLast('.', displayName).trim().ifBlank { displayName }
        return LocalAudioSnapshot(
            sourceKey = "mediastore:${row.id}",
            contentUri = "content://media/external/audio/media/${row.id}",
            displayName = displayName,
            relativeFolder = relativeFolder,
            title = title,
            album = row.album?.trim()?.takeIf(String::isNotEmpty),
            artistNames = splitArtists(row.artist),
            durationMs = row.durationMs?.takeIf { it > 0 },
            sizeBytes = row.sizeBytes,
            modifiedEpochSeconds = row.modifiedEpochSeconds.coerceAtLeast(0),
            artworkRef = row.albumId?.takeIf { it >= 0 }?.let { "content://media/external/audio/albumart/$it" },
        )
    }

    fun shouldRetainPreviousOnMappingFailure(row: MediaStoreRow): Boolean {
        if (row.id < 0) return false
        val displayName = row.displayName?.trim().orEmpty()
        if (displayName.isBlank() || row.sizeBytes < 0) return true
        val extension = displayName.substringAfterLast('.', "").lowercase()
        val normalizedMime = row.mimeType?.substringBefore(';')?.trim()?.lowercase()
        val mimeIsUnknown = normalizedMime == null || normalizedMime in GENERIC_MIME_TYPES
        return extension in SUPPORTED_EXTENSIONS || normalizedMime in SUPPORTED_MIME_TYPES || mimeIsUnknown
    }

    private fun isSupported(displayName: String, mimeType: String?): Boolean {
        val extension = displayName.substringAfterLast('.', "").lowercase()
        val normalizedMime = mimeType?.substringBefore(';')?.trim()?.lowercase()
        return extension in SUPPORTED_EXTENSIONS || normalizedMime in SUPPORTED_MIME_TYPES
    }

    private fun splitArtists(value: String?): List<String> = value
        ?.split(Regex("[;,]"))
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.distinctBy(String::lowercase)
        .orEmpty()

    companion object {
        private val SUPPORTED_EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav")
        private val GENERIC_MIME_TYPES = setOf("", "application/octet-stream", "binary/octet-stream")
        private val SUPPORTED_MIME_TYPES = setOf(
            "audio/mpeg",
            "audio/flac",
            "audio/x-flac",
            "audio/mp4",
            "audio/aac",
            "audio/ogg",
            "audio/opus",
            "audio/wav",
            "audio/x-wav",
        )
    }
}

internal fun normalizeFolder(value: String?): String {
    if (value.isNullOrBlank()) return ""
    var normalized = value.replace('\\', '/').replace(Regex("/+"), "/").trim('/')
    val storagePrefixes = listOf("storage/emulated/0/", "sdcard/")
    storagePrefixes.firstOrNull { normalized.startsWith(it, ignoreCase = true) }?.let { prefix ->
        normalized = normalized.drop(prefix.length)
    }
    val segments = normalized.split('/')
    normalized = when {
        segments.size >= 3 && segments[0].equals("storage", ignoreCase = true) -> segments.drop(2).joinToString("/")
        segments.size >= 4 && segments[0].equals("mnt", ignoreCase = true) &&
            segments[1].equals("media_rw", ignoreCase = true) -> segments.drop(3).joinToString("/")
        else -> normalized
    }
    return normalized.trim('/')
}
