package com.cleartune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRuntimeSettingsTest {
    @Test
    fun `service policy applies noisy and background playback settings`() {
        val provider = MutablePlaybackRuntimeSettingsProvider(
            PlaybackRuntimeSettings(
                pauseOnHeadphoneDisconnect = false,
                backgroundPlayback = false,
            ),
        )
        val policy = PlaybackServicePolicy(provider)
        assertFalse(policy.handleAudioBecomingNoisy)
        assertTrue(policy.shouldStopOnTaskRemoved(playWhenReady = true, mediaItemCount = 1))

        provider.update(
            PlaybackRuntimeSettings(
                pauseOnHeadphoneDisconnect = true,
                backgroundPlayback = true,
            ),
        )
        assertTrue(policy.handleAudioBecomingNoisy)
        assertFalse(policy.shouldStopOnTaskRemoved(playWhenReady = true, mediaItemCount = 1))
        assertTrue(policy.shouldStopOnTaskRemoved(playWhenReady = false, mediaItemCount = 1))
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

    @Test
    fun `streaming cache defaults to enabled with a 512 MB limit and live changes require rebuild`() {
        val defaults = PlaybackRuntimeSettings()
        assertEquals(512L * 1024 * 1024, cacheMaxBytesOrNull(defaults))

        val disabled = defaults.copy(streamingCacheEnabled = false)
        assertTrue(cacheConfigurationChanged(defaults, disabled))
        assertTrue(cacheConfigurationChanged(defaults, defaults.copy(cacheLimitBytes = 256L * 1024 * 1024)))
        assertFalse(cacheConfigurationChanged(defaults, defaults.copy(backgroundPlayback = true)))
    }
}
