package com.cleartune.core.player

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class RadioMediaSourceFactoryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val direct = DefaultDataSource.Factory(instrumentation.targetContext)

    @Test fun hlsRadioAcceptsExistingRadioMarkerAndKeepsMetadata() {
        val source = RadioMediaSourceFactory(direct, direct).createMediaSource(
            radio("https://radio.invalid/live.m3u8"),
        )
        assertTrue(source is HlsMediaSource)
        assertNull(source.mediaItem.localConfiguration?.customCacheKey)
        assertEquals("radio:test", source.mediaItem.mediaId)
        assertTrue(source.mediaItem.mediaMetadata.extras!!.getBoolean("com.cleartune.song.is_radio"))
    }

    @Test fun ordinarySongKeepsItsCacheIdentity() {
        val song = MediaItem.Builder().setUri("https://music.invalid/song.mp3")
            .setCustomCacheKey("cleartune:v2:server:user:song:maxBitRate=192").build()
        val source = RadioMediaSourceFactory(direct, direct).createMediaSource(song)
        assertEquals(song, source.mediaItem)
    }

    /** Opt-in device acceptance: pass -e radioTestUrl <public stream>, never an account secret. */
    @Test fun liveRadioPlaysAndReconnectsWithoutOpeningSongCache() {
        val url = InstrumentationRegistry.getArguments().getString("radioTestUrl")
        assumeNotNull(url)
        val forbiddenSongFactory = DataSource.Factory { error("Radio entered the song cache") }
        val factory = RadioMediaSourceFactory(forbiddenSongFactory, direct)
        assertTrue(factory.supportedTypes.contains(C.CONTENT_TYPE_HLS))
        var player: ExoPlayer? = null
        try {
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(instrumentation.targetContext)
                    .setMediaSourceFactory(factory).build().apply {
                        volume = 0f
                        setMediaItem(radio(url!!))
                        prepare()
                        play()
                    }
            }
            assertPlaybackProgress { player!! }
            instrumentation.runOnMainSync {
                checkNotNull(player).apply {
                    pause()
                    stop()
                    seekToDefaultPosition()
                    prepare()
                    play()
                }
            }
            assertPlaybackProgress { player!! }
        } finally {
            instrumentation.runOnMainSync { player?.release() }
        }
    }

    private fun assertPlaybackProgress(player: () -> ExoPlayer) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 40_000
        var lastPosition: Long? = null
        var advancedMillis = 0L
        var lastState = ""
        var progressed = false
        while (android.os.SystemClock.elapsedRealtime() < deadline && !progressed) {
            instrumentation.runOnMainSync {
                val current = player()
                assertNull(current.playerError)
                lastState = "state=${current.playbackState}, playing=${current.isPlaying}, " +
                    "suppression=${current.playbackSuppressionReason}, position=${current.currentPosition}, " +
                    "buffered=${current.totalBufferedDuration}, advanced=$advancedMillis"
                if (current.isPlaying) {
                    // A live HLS window can slide its origin backwards while audio keeps playing.
                    lastPosition?.let { previous ->
                        advancedMillis += (current.currentPosition - previous).coerceAtLeast(0)
                    }
                    lastPosition = current.currentPosition
                    progressed = advancedMillis >= 5_000
                }
            }
            if (!progressed) Thread.sleep(200)
        }
        assertTrue("Live audio must play and advance for five seconds: $lastState", progressed)
    }

    private fun radio(url: String): MediaItem = MediaItem.Builder()
        .setMediaId("radio:test")
        .setUri(url)
        .setCustomCacheKey("cleartune:radio:radio:test")
        .setMediaMetadata(MediaMetadata.Builder().setExtras(Bundle().apply {
            putBoolean("com.cleartune.song.is_radio", true)
        }).build())
        .build()
}
