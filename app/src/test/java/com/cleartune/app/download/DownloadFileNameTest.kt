package com.cleartune.app.download

import org.junit.Assert.*
import org.junit.Test

class DownloadFileNameTest {
    @Test fun readableNameWithoutTaskId() {
        assertEquals("周杰伦 - 晴天.flac", downloadFileName("周杰伦", "晴天", "flac"))
    }
    @Test fun removesPathCharactersAndKeepsFormat() {
        val name = downloadFileName("AC/DC", "../song:live", "flac")
        assertFalse(name.contains('/'))
        assertFalse(name.contains(':'))
        assertTrue(name.endsWith(".flac"))
        assertEquals("未知 - 未知.audio", downloadFileName("", "", "../../exe"))
    }
    @Test fun addsNumberOnlyWhenNameExists() {
        val name = "artist - song.flac"
        assertEquals(name, availableDownloadName(name) { false })
        assertEquals("artist - song (2).flac", availableDownloadName(name) { it == name })
        val existing = setOf(name, "artist - song (2).flac")
        assertEquals("artist - song (3).flac", availableDownloadName(name) { it in existing })
    }
}
