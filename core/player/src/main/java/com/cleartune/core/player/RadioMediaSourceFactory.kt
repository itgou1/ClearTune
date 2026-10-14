package com.cleartune.core.player

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource

/** Live manifests and segments must use the network directly, never the song cache. */
@UnstableApi
internal class RadioMediaSourceFactory private constructor(
    private val songFactory: DefaultMediaSourceFactory,
    private val radioFactory: DefaultMediaSourceFactory,
) : MediaSource.Factory by songFactory {
    constructor(songDataSourceFactory: DataSource.Factory, radioDataSourceFactory: DataSource.Factory) :
        this(DefaultMediaSourceFactory(songDataSourceFactory), DefaultMediaSourceFactory(radioDataSourceFactory))

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val isRadio = mediaItem.mediaMetadata.extras
            ?.getBoolean("com.cleartune.song.is_radio", false) == true ||
            mediaItem.localConfiguration?.customCacheKey?.startsWith("cleartune:radio:") == true
        return if (isRadio) {
            // HLS rejects a custom cache key. Selecting the direct factory also keeps
            // all child requests (playlist refreshes, segments, keys) out of the cache.
            radioFactory.createMediaSource(mediaItem.buildUpon().setCustomCacheKey(null).build())
        } else {
            songFactory.createMediaSource(mediaItem)
        }
    }
}
