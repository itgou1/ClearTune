package com.cleartune.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

internal object MediaItemFactory {
    fun create(descriptor: SecureMediaDescriptor): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(descriptor.title)
            .apply {
                descriptor.artworkUri?.let { setArtworkUri(Uri.parse(it)) }
            }
            .build()
        return MediaItem.Builder()
            .setMediaId(descriptor.mediaId)
            .setUri(PrivateMediaSourceRegistry.register(descriptor.mediaId, descriptor.playbackUri))
            .setMimeType(descriptor.mimeType)
            .setMediaMetadata(metadata)
            .build()
    }
}
