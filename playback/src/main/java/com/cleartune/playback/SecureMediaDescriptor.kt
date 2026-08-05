package com.cleartune.playback

import com.cleartune.core.model.Track
import com.cleartune.core.model.TrackLocation
import java.net.URI
import java.util.Locale

class SecureMediaDescriptor internal constructor(
    val mediaId: String,
    val title: String,
    val artworkUri: String?,
    val mimeType: String?,
    internal val playbackUri: String,
    internal val sourceId: String,
    internal val locationId: String,
) {
    override fun toString(): String =
        "SecureMediaDescriptor(mediaId=$mediaId, title=$title, artworkUri=$artworkUri, mimeType=$mimeType, playbackUri=<redacted>)"
}

object SecureMediaDescriptorFactory {
    fun create(track: Track, location: TrackLocation): SecureMediaDescriptor {
        val parsed = runCatching { URI(location.uri) }.getOrNull()
        require(parsed?.rawUserInfo.isNullOrEmpty()) {
            "Playback URLs must not contain embedded credentials"
        }
        return SecureMediaDescriptor(
            mediaId = track.id.value,
            title = track.title,
            artworkUri = track.artworkRef?.takeIf(::containsNoEmbeddedCredentials),
            mimeType = mimeTypeFor(location.uri),
            playbackUri = location.uri,
            sourceId = location.sourceId.value,
            locationId = location.id.value,
        )
    }

    private fun containsNoEmbeddedCredentials(uri: String): Boolean =
        runCatching { URI(uri).rawUserInfo.isNullOrEmpty() }.getOrDefault(false)

    private fun mimeTypeFor(uri: String): String? {
        val clean = uri.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
        return when (clean.substringAfterLast('.', missingDelimiterValue = "")) {
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "ogg", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            else -> null
        }
    }
}
