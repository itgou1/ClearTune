package com.cleartune.data.webdav

import com.cleartune.core.model.SourceId
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedArtworkCacheTest {
    @Test
    fun `store is contained atomic idempotent and replaces old format`() = runTest {
        val root = Files.createTempDirectory("artwork-cache-").toFile()
        try {
            val cache = EmbeddedArtworkCache(root, maximumArtworkBytes = 16)
            val source = SourceId("source")

            val jpegRef = requireNotNull(cache.store(source, "album/song.mp3", "image/jpeg", byteArrayOf(1, 2, 3)))
            val sameRef = requireNotNull(cache.store(source, "album/song.mp3", "image/jpeg", byteArrayOf(1, 2, 3)))
            assertEquals(jpegRef, sameRef)
            assertArrayEquals(byteArrayOf(1, 2, 3), File(java.net.URI(jpegRef)).readBytes())

            val pngRef = requireNotNull(cache.store(source, "album/song.mp3", "image/png", byteArrayOf(4, 5)))
            assertFalse(File(java.net.URI(jpegRef)).exists())
            assertArrayEquals(byteArrayOf(4, 5), File(java.net.URI(pngRef)).readBytes())
            assertEquals(1, root.walkTopDown().count(File::isFile))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `invalid type oversize and source cleanup cannot escape or affect siblings`() = runTest {
        val root = Files.createTempDirectory("artwork-cache-policy-").toFile()
        try {
            val cache = EmbeddedArtworkCache(root, maximumArtworkBytes = 4)
            val first = SourceId("first/../source")
            val second = SourceId("second")

            assertNull(cache.store(first, "key", "image/gif", byteArrayOf(1)))
            assertNull(cache.store(first, "key", "image/jpeg", ByteArray(5)))
            val firstRef = requireNotNull(cache.store(first, "key", "image/jpeg", byteArrayOf(1, 2)))
            val secondRef = requireNotNull(cache.store(second, "key", "image/png", byteArrayOf(3, 4)))

            cache.clearSource(first)

            assertFalse(File(java.net.URI(firstRef)).exists())
            assertTrue(File(java.net.URI(secondRef)).isFile)
            assertTrue(File(java.net.URI(secondRef)).canonicalFile.toPath().startsWith(root.canonicalFile.toPath()))
        } finally {
            root.deleteRecursively()
        }
    }
}
