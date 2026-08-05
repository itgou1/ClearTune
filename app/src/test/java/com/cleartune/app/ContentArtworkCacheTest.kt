package com.cleartune.app

import com.cleartune.core.model.SourceId
import com.cleartune.data.webdav.EmbeddedArtworkCache
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentArtworkCacheTest {
    @Test
    fun `cached files are published as contained credential-free content uris`() = runBlocking {
        val root = Files.createTempDirectory("content-art-").toFile()
        val cache = ContentArtworkCache(root, "com.cleartune.app.artwork", EmbeddedArtworkCache(root))

        val reference = requireNotNull(cache.store(SourceId("source"), "Album/song.flac", "image/jpeg", byteArrayOf(1, 2, 3)))

        assertTrue(reference.startsWith("content://com.cleartune.app.artwork/"))
        assertFalse(reference.contains("file:"))
        assertFalse(reference.contains("Album/song.flac"))
        assertTrue(requireNotNull(cache.sourceUriPrefix(SourceId("source"))).startsWith("content://"))
    }
}
