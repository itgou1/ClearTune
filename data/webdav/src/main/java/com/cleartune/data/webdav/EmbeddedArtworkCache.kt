package com.cleartune.data.webdav

import com.cleartune.core.model.SourceId
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface ArtworkCache {
    suspend fun store(sourceId: SourceId, sourceKey: String, mimeType: String, bytes: ByteArray): String?
    suspend fun remove(sourceId: SourceId, sourceKey: String)
    suspend fun clearSource(sourceId: SourceId)
    fun sourceUriPrefix(sourceId: SourceId): String?

    data object None : ArtworkCache {
        override suspend fun store(
            sourceId: SourceId,
            sourceKey: String,
            mimeType: String,
            bytes: ByteArray,
        ): String? = null

        override suspend fun remove(sourceId: SourceId, sourceKey: String) = Unit
        override suspend fun clearSource(sourceId: SourceId) = Unit
        override fun sourceUriPrefix(sourceId: SourceId): String? = null
    }
}

class EmbeddedArtworkCache(
    rootDirectory: File,
    private val maximumArtworkBytes: Int = DEFAULT_MAXIMUM_ARTWORK_BYTES,
) : ArtworkCache {
    private val root = rootDirectory.canonicalFile
    private val mutex = Mutex()

    init {
        require(maximumArtworkBytes > 0)
        check(root.mkdirs() || root.isDirectory)
    }

    override suspend fun store(
        sourceId: SourceId,
        sourceKey: String,
        mimeType: String,
        bytes: ByteArray,
    ): String? = mutex.withLock {
        val extension = EXTENSIONS[mimeType.lowercase()]
        if (extension == null || bytes.isEmpty() || bytes.size > maximumArtworkBytes) {
            removeUnlocked(sourceId, sourceKey)
            return@withLock null
        }
        val directory = sourceDirectory(sourceId).apply { check(mkdirs() || isDirectory) }
        val baseName = digest(sourceKey)
        val destination = contained(File(directory, "$baseName.$extension"))
        val temporary = contained(File(directory, ".$baseName-${System.nanoTime()}.tmp"))
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            directory.listFiles().orEmpty()
                .filter { it.isFile && it.name.startsWith("$baseName.") && it != destination }
                .forEach { stale -> check(stale.delete() || !stale.exists()) }
            destination.toURI().toString()
        } finally {
            if (temporary.exists()) check(temporary.delete())
        }
    }

    override suspend fun remove(sourceId: SourceId, sourceKey: String) = mutex.withLock {
        removeUnlocked(sourceId, sourceKey)
    }

    override suspend fun clearSource(sourceId: SourceId) = mutex.withLock {
        val directory = sourceDirectory(sourceId)
        if (!directory.exists()) return@withLock
        directory.walkBottomUp().forEach { candidate ->
            contained(candidate)
            check(candidate.delete() || !candidate.exists())
        }
    }

    override fun sourceUriPrefix(sourceId: SourceId): String = sourceDirectory(sourceId).toURI().toString()

    private fun removeUnlocked(sourceId: SourceId, sourceKey: String) {
        val directory = sourceDirectory(sourceId)
        if (!directory.isDirectory) return
        val baseName = digest(sourceKey)
        directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith("$baseName.") }
            .forEach { candidate ->
                contained(candidate)
                check(candidate.delete() || !candidate.exists())
            }
    }

    private fun sourceDirectory(sourceId: SourceId): File = contained(File(root, digest(sourceId.value)))

    private fun contained(file: File): File = file.canonicalFile.also { canonical ->
        check(canonical.toPath().startsWith(root.toPath())) { "Artwork path escapes its root" }
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val DEFAULT_MAXIMUM_ARTWORK_BYTES = 512 * 1024
        val EXTENSIONS = mapOf("image/jpeg" to "jpg", "image/png" to "png")
    }
}
