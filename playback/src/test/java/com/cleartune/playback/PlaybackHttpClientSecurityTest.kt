package com.cleartune.playback

import org.junit.Assert.assertFalse
import org.junit.Test

class PlaybackHttpClientSecurityTest {
    @Test
    fun `playback transport rejects redirects before credentials can cross source boundaries`() {
        val client = securePlaybackHttpClient()

        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
    }
}
