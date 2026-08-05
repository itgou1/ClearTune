package com.cleartune.data.download

import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer

data class DownloadPaths(
    val partialFile: File,
    val finalFile: File,
)

class DownloadFilePolicy(private val root: File) {
    fun paths(sourceId: String, trackId: String, suggestedName: String): DownloadPaths {
        val sourceDirectory = root.resolve(stableSegment(sourceId))
        val trackDirectory = sourceDirectory.resolve(stableSegment(trackId))
        val finalFile = trackDirectory.resolve(safeFileName(suggestedName))
        val partialFile = trackDirectory.resolve(finalFile.name + PARTIAL_SUFFIX)
        requireContained(finalFile)
        requireContained(partialFile)
        return DownloadPaths(partialFile, finalFile)
    }

    private fun stableSegment(value: String): String = digest(value).take(24)

    private fun safeFileName(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace(UNSAFE_CHARACTERS, "_")
            .replace(WHITESPACE, " ")
            .trim(' ', '.')
        val dot = normalized.lastIndexOf('.')
        val requestedExtension = if (dot in 1 until normalized.lastIndex) {
            normalized.substring(dot).take(MAX_EXTENSION_LENGTH)
        } else {
            ""
        }
        var stem = if (requestedExtension.isEmpty()) normalized else normalized.dropLast(requestedExtension.length)
        if (stem.isBlank() || stem.uppercase() in WINDOWS_RESERVED_NAMES) stem = "track"
        val suffix = "-${digest(value).take(10)}"
        val maxStem = MAX_FILE_NAME_LENGTH - requestedExtension.length - suffix.length
        stem = stem.take(maxStem.coerceAtLeast(1)).trimEnd(' ', '.')
        return "$stem$suffix$requestedExtension"
    }

    private fun requireContained(file: File) {
        val rootPath = root.canonicalFile.toPath()
        require(file.canonicalFile.toPath().startsWith(rootPath)) { "Download path escapes its root" }
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val PARTIAL_SUFFIX = ".part"
        const val MAX_FILE_NAME_LENGTH = 120
        const val MAX_EXTENSION_LENGTH = 12
        val UNSAFE_CHARACTERS = Regex("[\\u0000-\\u001f\\u007f<>:\"/\\\\|?*]")
        val WHITESPACE = Regex("\\s+")
        val WINDOWS_RESERVED_NAMES = buildSet {
            addAll(listOf("CON", "PRN", "AUX", "NUL"))
            addAll((1..9).map { "COM$it" })
            addAll((1..9).map { "LPT$it" })
        }
    }
}
