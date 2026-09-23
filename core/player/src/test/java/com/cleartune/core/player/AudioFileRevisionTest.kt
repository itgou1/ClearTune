package com.cleartune.core.player

import org.junit.Assert.*
import org.junit.Test

class AudioFileRevisionTest {
    @Test fun editedAudioCannotUseOldQualityVariants() {
        val old = "cleartune:v2:https://example.com/rest/stream.view:user=u:song=s:maxBitRate=128:format=mp3"
        val revised = revisedPlaybackCacheKey(old, 1)
        assertEquals(old, revisedPlaybackCacheKey(old, 0))
        assertNotEquals(old.substringBeforeLast(":maxBitRate="), revised.substringBeforeLast(":maxBitRate="))
        assertEquals(revised.substringBeforeLast(":maxBitRate="),
            revisedPlaybackCacheKey(old.replace("128", "320"), 1).substringBeforeLast(":maxBitRate="))
    }
}
