package com.cleartune.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MusicTagPathTest {
    @Test fun mapsRelativeAndAbsolutePathsWithoutChangingNames() {
        assertEquals("/app/media/歌手/01 %#.flac", MusicTagPath.resolve("歌手/01 %#.flac", "", "/app/media/"))
        assertEquals("/app/media/a.flac", MusicTagPath.resolve("/music/a.flac", "/music", "/app/media"))
    }
    @Test fun rejectsTraversalTemplateAndUnmappedAbsolutePaths() {
        listOf("../a.flac", "a/../b.flac", "a/./b.flac", "a\\b.flac", "/other/a.flac", "\${title}.flac", "").forEach {
            assertThrows(IllegalArgumentException::class.java) { MusicTagPath.resolve(it, "/music", "/app/media") }
        }
    }
    @Test fun sourcePrefixMustEndAtDirectoryBoundary() {
        assertThrows(IllegalArgumentException::class.java) { MusicTagPath.resolve("/music2/a.flac", "/music", "/app/media") }
    }
    @Test fun credentialsAreNotIncludedInDebugString() {
        assertEquals("MusicTagSettings(configured=true)", MusicTagSettings("https://example.com", "user", "secret").toString())
    }
}
