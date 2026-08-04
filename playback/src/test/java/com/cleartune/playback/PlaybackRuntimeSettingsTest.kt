package com.cleartune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRuntimeSettingsTest {
    @Test
    fun `service policy applies noisy and background playback settings`() {
        val disabled = PlaybackServicePolicy(
            PlaybackRuntimeSettings(
                pauseOnHeadphoneDisconnect = false,
                backgroundPlayback = false,
            ),
        )
        assertFalse(disabled.handleAudioBecomingNoisy)
        assertTrue(disabled.shouldStopOnTaskRemoved(playWhenReady = true, mediaItemCount = 1))

        val enabled = PlaybackServicePolicy(
            PlaybackRuntimeSettings(
                pauseOnHeadphoneDisconnect = true,
                backgroundPlayback = true,
            ),
        )
        assertTrue(enabled.handleAudioBecomingNoisy)
        assertFalse(enabled.shouldStopOnTaskRemoved(playWhenReady = true, mediaItemCount = 1))
        assertTrue(enabled.shouldStopOnTaskRemoved(playWhenReady = false, mediaItemCount = 1))
    }

    @Test
    fun `streaming cache policy disables cache or applies configured byte limit`() {
        assertNull(
            cacheMaxBytesOrNull(
                PlaybackRuntimeSettings(streamingCacheEnabled = false, cacheLimitBytes = 64L * 1024 * 1024),
            ),
        )
        assertEquals(
            256L * 1024 * 1024,
            cacheMaxBytesOrNull(
                PlaybackRuntimeSettings(streamingCacheEnabled = true, cacheLimitBytes = 256L * 1024 * 1024),
            ),
        )
    }
}
