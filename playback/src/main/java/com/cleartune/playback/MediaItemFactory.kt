package com.cleartune.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.net.URI

internal object MediaItemFactory {
    data class SanitizedMetadata(
        val title: String,
        val artist: String?,
        val album: String?,
        val artworkUri: String?,
    )

    fun create(
        descriptor: SecureMediaDescriptor,
        artist: String? = null,
        album: String? = null,
    ): MediaItem {
        val sanitized = sanitizeMetadata(descriptor.title, artist, album, descriptor.artworkUri)
        val metadata = MediaMetadata.Builder()
            .setTitle(sanitized.title)
            .setArtist(sanitized.artist)
            .setAlbumTitle(sanitized.album)
            .apply {
                sanitized.artworkUri?.let { setArtworkUri(Uri.parse(it)) }
            }
            .build()
        return MediaItem.Builder()
            .setMediaId(descriptor.mediaId)
            .setUri(PrivateMediaSourceRegistry.register(descriptor.mediaId, descriptor.playbackUri))
            .setMimeType(descriptor.mimeType)
            .setMediaMetadata(metadata)
            .build()
    }

    fun sanitizeMetadata(
        title: String,
        artist: String?,
        album: String?,
        artworkUri: String?,
    ): SanitizedMetadata = SanitizedMetadata(
        title = sanitizeText(title).ifBlank { "Unknown track" },
        artist = artist?.let(::sanitizeText)?.takeIf(String::isNotBlank),
        album = album?.let(::sanitizeText)?.takeIf(String::isNotBlank),
        artworkUri = artworkUri?.takeIf(::isSafeArtworkUri),
    )

    private fun sanitizeText(value: String): String = value
        .replace(Regex("[\\p{Cc}\\p{Cf}\\s]+"), " ")
        .trim()
        .take(MAX_METADATA_LENGTH)

    private fun isSafeArtworkUri(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.rawUserInfo.isNullOrEmpty() && uri.scheme?.lowercase() in SAFE_ARTWORK_SCHEMES
    }.getOrDefault(false)

    private const val MAX_METADATA_LENGTH = 512
    private val SAFE_ARTWORK_SCHEMES = setOf("content", "android.resource", "http", "https")
}
