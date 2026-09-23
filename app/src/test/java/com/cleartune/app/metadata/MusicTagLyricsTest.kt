package com.cleartune.app.metadata

import org.junit.Assert.*
import org.junit.Test

class MusicTagLyricsTest {
    @Test fun comparesLrcWithServerStructuredLyricsWithoutAcceptingOldText() {
        val expected = normalizedTagLyrics("[ar:歌手]\r\n[00:01.20]第一句\n[00:03.000]第二句")
        assertEquals(listOf("第一句", "第二句"), expected)
        assertEquals(expected, normalizedTagLyrics("第一句\n第二句"))
        assertNotEquals(expected, normalizedTagLyrics("旧歌词"))
    }
}
