package com.cleartune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
