package com.cleartune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MediaItemFactoryTest {
    @Test
    fun `session media item contains opaque uri instead of private remote url`() {
        val privateUri = "https://private.example/music/song.mp3"
        val sessionUri = PrivateMediaSourceRegistry.register("track", privateUri)

        assertEquals(privateUri, PrivateMediaSourceRegistry.resolve(sessionUri))
        assertEquals(true, sessionUri.startsWith("cleartune-media://"))
        assertFalse(sessionUri.contains("private.example"))
    }

    @Test
    fun `same URL from different sources retains distinct private playback identities`() {
        val uri = "https://music.example/shared/song.mp3"
        val first = PrivateMediaSourceRegistry.register(
            "track",
            PrivateMediaSource(uri, sourceId = "source-a", locationId = "location-a"),
        )
        val second = PrivateMediaSourceRegistry.register(
            "track",
            PrivateMediaSource(uri, sourceId = "source-b", locationId = "location-b"),
        )

        assertNotEquals(first, second)
        assertEquals("source-a", PrivateMediaSourceRegistry.resolveEntry(first)?.sourceId)
        assertEquals("source-b", PrivateMediaSourceRegistry.resolveEntry(second)?.sourceId)
    }
}
