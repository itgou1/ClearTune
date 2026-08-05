package com.cleartune.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.cleartune.core.model.SourceId
import com.cleartune.data.webdav.ArtworkCache
import java.io.File
import java.net.URI
import java.util.Base64

class ContentArtworkCache(
    rootDirectory: File,
    private val authority: String,
    private val delegate: ArtworkCache,
) : ArtworkCache {
    private val root = rootDirectory.canonicalFile

    override suspend fun store(sourceId: SourceId, sourceKey: String, mimeType: String, bytes: ByteArray): String? =
        delegate.store(sourceId, sourceKey, mimeType, bytes)?.let(::publish)

    override suspend fun remove(sourceId: SourceId, sourceKey: String) = delegate.remove(sourceId, sourceKey)
    override suspend fun clearSource(sourceId: SourceId) = delegate.clearSource(sourceId)
    override fun sourceUriPrefix(sourceId: SourceId): String? = delegate.sourceUriPrefix(sourceId)?.let(::publish)

    private fun publish(fileReference: String): String {
        val file = File(URI(fileReference)).canonicalFile
        require(file.toPath().startsWith(root.toPath())) { "Artwork path escapes its root" }
        val relative = root.toPath().relativize(file.toPath())
        val encodedPath = relative.joinToString("/") { segment ->
            Base64.getUrlEncoder().withoutPadding().encodeToString(segment.toString().toByteArray(Charsets.UTF_8))
        }
        return "content://$authority/$encodedPath"
    }
}

class ArtworkContentProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r") { "Artwork provider is read-only" }
        val context = requireNotNull(context)
        val root = File(context.filesDir, "webdav_artwork_cache").canonicalFile
        val file = uri.pathSegments.fold(root) { parent, segment ->
            val decoded = String(Base64.getUrlDecoder().decode(segment), Charsets.UTF_8)
            File(parent, decoded)
        }.canonicalFile
        require(file.toPath().startsWith(root.toPath()) && file.isFile) { "Unknown artwork" }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = when (uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        else -> null
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
