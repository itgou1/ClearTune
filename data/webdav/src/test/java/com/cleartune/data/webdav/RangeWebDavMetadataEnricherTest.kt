package com.cleartune.data.webdav

import com.cleartune.core.model.MusicSource
import com.cleartune.core.model.SourceId
import com.cleartune.core.model.SourceType
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeWebDavMetadataEnricherTest {
    @Test
    fun `MP3 ID3 fields use one exact bounded head range`() = runTest {
        val requests = mutableListOf<LongRange>()
        val fixture = id3(
            "TIT2" to text("Range Song"),
            "TALB" to text("Range Album"),
            "TPE1" to text("Aster"),
            "TLEN" to text("123456"),
        )
        val enricher = RangeWebDavMetadataEnricher(WebDavRangeReader { _, _, start, end, _ ->
            requests += start..end
            RangeResponse(fixture, "bytes 0-${fixture.lastIndex}/500000", true, "etag")
        })

        val metadata = enricher.enrich(source(), entry("song.mp3", 500_000))

        assertEquals(listOf(0L..262_143L), requests)
        assertEquals("Range Song", metadata.title)
        assertEquals("Range Album", metadata.albumTitle)
        assertEquals(listOf("Aster"), metadata.artistNames)
        assertEquals(123_456L, metadata.durationMs)
    }

    @Test
    fun `FLAC Vorbis comment and stream info are parsed from bounded head`() = runTest {
        val fixture = flac("Flac Song", "Flac Album", "Boreal", sampleRate = 48_000, totalSamples = 96_000)
        val enricher = RangeWebDavMetadataEnricher(WebDavRangeReader { _, _, _, _, _ ->
            RangeResponse(fixture, "bytes 0-${fixture.lastIndex}/${fixture.size}", true, null)
        })

        val metadata = enricher.enrich(source(), entry("fallback.flac", fixture.size.toLong()))

        assertEquals("Flac Song", metadata.title)
        assertEquals("Flac Album", metadata.albumTitle)
        assertEquals(listOf("Boreal"), metadata.artistNames)
        assertEquals(2_000L, metadata.durationMs)
    }

    @Test
    fun `ignored Range response falls back without using returned tag bytes`() = runTest {
        val enricher = RangeWebDavMetadataEnricher(WebDavRangeReader { _, _, _, _, _ ->
            RangeResponse(id3("TIT2" to text("Must not be used")), null, false, null)
        })

        val metadata = enricher.enrich(source(), entry("Folder Name.mp3", 1_000_000))

        assertEquals("Folder Name", metadata.title)
        assertNull(metadata.albumTitle)
        assertTrue(metadata.artistNames.isEmpty())
    }

    @Test
    fun `overflowing ID3 frame length falls back to filename`() = runTest {
        val fixture = ByteArray(20).apply {
            "ID3".toByteArray().copyInto(this)
            this[3] = 3
            this[9] = 10
            "TIT2".toByteArray().copyInto(this, 10)
            byteArrayOf(0x7f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte()).copyInto(this, 14)
        }
        val enricher = RangeWebDavMetadataEnricher(WebDavRangeReader { _, _, _, _, _ ->
            RangeResponse(fixture, "bytes 0-${fixture.lastIndex}/${fixture.size}", true, null)
        })

        val metadata = enricher.enrich(source(), entry("Malformed Song.mp3", fixture.size.toLong()))

        assertEquals("Malformed Song", metadata.title)
        assertNull(metadata.albumTitle)
    }

    @Test
    fun `overflowing FLAC Vorbis vendor length falls back to filename`() = runTest {
        val fixture = "fLaC".toByteArray() +
            byteArrayOf(0x84.toByte(), 0, 0, 8) +
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(Int.MAX_VALUE).array() +
            byteArrayOf(0, 0, 0, 0)
        val enricher = RangeWebDavMetadataEnricher(WebDavRangeReader { _, _, _, _, _ ->
            RangeResponse(fixture, "bytes 0-${fixture.lastIndex}/${fixture.size}", true, null)
        })

        val metadata = enricher.enrich(source(), entry("Malformed Album.flac", fixture.size.toLong()))

        assertEquals("Malformed Album", metadata.title)
        assertTrue(metadata.artistNames.isEmpty())
    }

    @Test
    fun `MP3 APIC accepts a real JPEG and persists it as a contained local reference`() = runTest {
        val root = Files.createTempDirectory("mp3-artwork-").toFile()
        try {
            val artwork = JPEG_1X1
            val fixture = id3(
                "TIT2" to text("Artwork Song"),
                "APIC" to apic("image/jpeg", artwork),
            )
            val enricher = RangeWebDavMetadataEnricher(
                reader = WebDavRangeReader { _, _, _, _, _ ->
                    RangeResponse(fixture, "bytes 0-${fixture.lastIndex}/${fixture.size}", true, null)
                },
                artworkCache = EmbeddedArtworkCache(root, maximumArtworkBytes = 1_024),
            )

            val metadata = enricher.enrich(source(), entry("art.mp3", fixture.size.toLong()))

            val file = java.io.File(java.net.URI(requireNotNull(metadata.artworkRef)))
            assertArrayEquals(artwork, file.readBytes())
            assertTrue(file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `FLAC PICTURE accepts a real PNG and rejects malicious data length`() = runTest {
        val root = Files.createTempDirectory("flac-artwork-").toFile()
        try {
            val pictureBytes = PNG_1X1
            val valid = flacWithPicture("Picture Song", "image/png", pictureBytes, pictureBytes.size)
            val malicious = flacWithPicture("Broken Picture", "image/png", byteArrayOf(1), Int.MAX_VALUE)
            val responses = ArrayDeque(listOf(valid, malicious))
            val enricher = RangeWebDavMetadataEnricher(
                reader = WebDavRangeReader { _, _, _, _, _ ->
                    val bytes = responses.removeFirst()
                    RangeResponse(bytes, "bytes 0-${bytes.lastIndex}/${bytes.size}", true, null)
                },
                artworkCache = EmbeddedArtworkCache(root, maximumArtworkBytes = 1_024),
            )

            val accepted = enricher.enrich(source(), entry("valid.flac", valid.size.toLong()))
            val rejected = enricher.enrich(source(), entry("malicious.flac", malicious.size.toLong()))

            assertArrayEquals(pictureBytes, java.io.File(java.net.URI(requireNotNull(accepted.artworkRef))).readBytes())
            assertEquals("Broken Picture", rejected.title)
            assertNull(rejected.artworkRef)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `MP3 APIC also accepts a real PNG`() = runTest {
        val cache = RecordingArtworkCache()
        val fixture = id3(
            "TIT2" to text("PNG Cover"),
            "APIC" to apic("image/png", PNG_1X1),
        )
        val enricher = RangeWebDavMetadataEnricher(
            reader = WebDavRangeReader { _, _, _, _, _ ->
                RangeResponse(fixture, "bytes 0-${fixture.lastIndex}/${fixture.size}", true, null)
            },
            artworkCache = cache,
        )

        val metadata = enricher.enrich(source(), entry("png-cover.mp3", fixture.size.toLong()))

        assertTrue(metadata.artworkRef != null)
        assertArrayEquals(PNG_1X1, cache.stored.single())
    }

    @Test
    fun `FLAC PICTURE also accepts a real JPEG`() = runTest {
        val cache = RecordingArtworkCache()
        val fixture = flacWithPicture(
            "JPEG Cover",
            "image/jpeg",
            JPEG_1X1,
            JPEG_1X1.size,
        )
        val enricher = RangeWebDavMetadataEnricher(
            reader = WebDavRangeReader { _, _, _, _, _ ->
                RangeResponse(fixture, "bytes 0-${fixture.lastIndex}/${fixture.size}", true, null)
            },
            artworkCache = cache,
        )

        val metadata = enricher.enrich(source(), entry("jpeg-cover.flac", fixture.size.toLong()))

        assertTrue(metadata.artworkRef != null)
        assertArrayEquals(JPEG_1X1, cache.stored.single())
    }

    @Test
    fun `validated artwork still obeys the configured compressed byte limit`() = runTest {
        val cache = RecordingArtworkCache()
        val fixture = id3(
            "TIT2" to text("Oversized Cover"),
            "APIC" to apic("image/jpeg", JPEG_1X1),
        )
        val enricher = RangeWebDavMetadataEnricher(
            reader = WebDavRangeReader { _, _, _, _, _ ->
                RangeResponse(fixture, "bytes 0-${fixture.lastIndex}/${fixture.size}", true, null)
            },
            maximumArtworkBytes = JPEG_1X1.size - 1,
            artworkCache = cache,
        )

        val metadata = enricher.enrich(source(), entry("oversized.mp3", fixture.size.toLong()))

        assertNull(metadata.artworkRef)
        assertTrue(cache.stored.isEmpty())
    }

    @Test
    fun `MP3 APIC rejects untrusted image headers before calling the cache`() = runTest {
        val invalidArtwork = listOf(
            "arbitrary bytes" to ("image/jpeg" to byteArrayOf(1, 2, 3, 4)),
            "truncated JPEG" to ("image/jpeg" to JPEG_1X1.copyOf(16)),
            "truncated PNG" to ("image/png" to PNG_1X1.copyOf(24)),
            "MIME spoof" to ("image/png" to JPEG_1X1),
            "excessive dimension" to ("image/png" to PNG_1X1.withPngDimensions(8_193, 1)),
            "excessive pixels" to ("image/png" to PNG_1X1.withPngDimensions(8_192, 4_097)),
        )

        invalidArtwork.forEach { (case, declaredAndBytes) ->
            val (mimeType, artwork) = declaredAndBytes
            val cache = RecordingArtworkCache()
            val fixture = id3(
                "TIT2" to text("Invalid $case"),
                "APIC" to apic(mimeType, artwork),
            )
            val enricher = RangeWebDavMetadataEnricher(
                reader = WebDavRangeReader { _, _, _, _, _ ->
                    RangeResponse(fixture, "bytes 0-${fixture.lastIndex}/${fixture.size}", true, null)
                },
                artworkCache = cache,
            )

            val metadata = enricher.enrich(source(), entry("$case.mp3", fixture.size.toLong()))

            assertNull("$case must not produce an artwork reference", metadata.artworkRef)
            assertTrue("$case must not reach the cache", cache.stored.isEmpty())
        }
    }

    @Test
    fun `FLAC PICTURE rejects spoofed truncated and pathological images before caching`() = runTest {
        data class InvalidPicture(
            val name: String,
            val mimeType: String,
            val image: ByteArray,
            val width: Int = 1,
            val height: Int = 1,
        )
        val invalidPictures = listOf(
            InvalidPicture("arbitrary bytes", "image/png", byteArrayOf(9, 8, 7, 6)),
            InvalidPicture("truncated PNG", "image/png", PNG_1X1.copyOf(24)),
            InvalidPicture("MIME spoof", "image/jpeg", PNG_1X1),
            InvalidPicture(
                "excessive dimension",
                "image/png",
                PNG_1X1.withPngDimensions(8_193, 1),
                width = 8_193,
            ),
            InvalidPicture(
                "excessive pixels",
                "image/png",
                PNG_1X1.withPngDimensions(8_192, 4_097),
                width = 8_192,
                height = 4_097,
            ),
            InvalidPicture("declared dimension spoof", "image/png", PNG_1X1, width = 2),
        )

        invalidPictures.forEach { picture ->
            val cache = RecordingArtworkCache()
            val fixture = flacWithPicture(
                title = "Invalid ${picture.name}",
                mime = picture.mimeType,
                image = picture.image,
                declaredImageLength = picture.image.size,
                width = picture.width,
                height = picture.height,
            )
            val enricher = RangeWebDavMetadataEnricher(
                reader = WebDavRangeReader { _, _, _, _, _ ->
                    RangeResponse(fixture, "bytes 0-${fixture.lastIndex}/${fixture.size}", true, null)
                },
                artworkCache = cache,
            )

            val metadata = enricher.enrich(
                source(),
                entry("${picture.name}.flac", fixture.size.toLong()),
            )

            assertNull("${picture.name} must not produce an artwork reference", metadata.artworkRef)
            assertTrue("${picture.name} must not reach the cache", cache.stored.isEmpty())
        }
    }

    private fun source() = MusicSource(
        SourceId("source"), "Remote", SourceType.WEBDAV, "https://music.example/dav/",
    )

    private fun entry(name: String, size: Long) = WebDavEntry(
        "https://music.example/dav/$name".replace(" ", "%20").toHttpUrl(), name, false, size, null,
    )

    private fun text(value: String) = byteArrayOf(3) + value.toByteArray(Charsets.UTF_8)

    private fun apic(mimeType: String, artwork: ByteArray): ByteArray =
        byteArrayOf(3) + mimeType.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 3, 0) + artwork

    private fun id3(vararg frames: Pair<String, ByteArray>): ByteArray {
        val body = ByteArrayOutputStream()
        frames.forEach { (id, payload) ->
            body.write(id.toByteArray(Charsets.US_ASCII))
            body.write(ByteBuffer.allocate(4).putInt(payload.size).array())
            body.write(byteArrayOf(0, 0))
            body.write(payload)
        }
        val bytes = body.toByteArray()
        val size = byteArrayOf(
            ((bytes.size ushr 21) and 0x7f).toByte(),
            ((bytes.size ushr 14) and 0x7f).toByte(),
            ((bytes.size ushr 7) and 0x7f).toByte(),
            (bytes.size and 0x7f).toByte(),
        )
        return "ID3".toByteArray() + byteArrayOf(3, 0, 0) + size + bytes
    }

    private fun flac(title: String, album: String, artist: String, sampleRate: Int, totalSamples: Long): ByteArray {
        val streamInfo = ByteArray(34)
        var packed = (sampleRate.toLong() shl 44) or (1L shl 41) or (15L shl 36) or totalSamples
        repeat(8) { index -> streamInfo[17 - index] = packed.toByte().also { packed = packed ushr 8 } }
        val comments = listOf("TITLE=$title", "ALBUM=$album", "ARTIST=$artist")
        val vorbis = ByteArrayOutputStream().apply {
            write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0).array())
            write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(comments.size).array())
            comments.forEach { comment ->
                val value = comment.toByteArray()
                write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.size).array())
                write(value)
            }
        }.toByteArray()
        fun block(type: Int, last: Boolean, data: ByteArray): ByteArray =
            byteArrayOf(((if (last) 0x80 else 0) or type).toByte(), (data.size ushr 16).toByte(), (data.size ushr 8).toByte(), data.size.toByte()) + data
        return "fLaC".toByteArray() + block(0, false, streamInfo) + block(4, true, vorbis)
    }

    private fun flacWithPicture(
        title: String,
        mime: String,
        image: ByteArray,
        declaredImageLength: Int,
        width: Int = 1,
        height: Int = 1,
    ): ByteArray {
        val comment = "TITLE=$title".toByteArray()
        val vorbis = ByteArrayOutputStream().apply {
            write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0).array())
            write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(1).array())
            write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(comment.size).array())
            write(comment)
        }.toByteArray()
        val mimeBytes = mime.toByteArray()
        val picture = ByteArrayOutputStream().apply {
            write(ByteBuffer.allocate(4).putInt(3).array())
            write(ByteBuffer.allocate(4).putInt(mimeBytes.size).array())
            write(mimeBytes)
            write(ByteBuffer.allocate(4).putInt(0).array())
            write(ByteBuffer.allocate(4).putInt(width).array())
            write(ByteBuffer.allocate(4).putInt(height).array())
            write(ByteBuffer.allocate(4).putInt(24).array())
            write(ByteBuffer.allocate(4).putInt(0).array())
            write(ByteBuffer.allocate(4).putInt(declaredImageLength).array())
            write(image)
        }.toByteArray()
        fun block(type: Int, last: Boolean, data: ByteArray): ByteArray =
            byteArrayOf(
                ((if (last) 0x80 else 0) or type).toByte(),
                (data.size ushr 16).toByte(),
                (data.size ushr 8).toByte(),
                data.size.toByte(),
            ) + data
        return "fLaC".toByteArray() + block(4, false, vorbis) + block(6, true, picture)
    }

    private fun ByteArray.withPngDimensions(width: Int, height: Int): ByteArray = copyOf().also { png ->
        ByteBuffer.wrap(png, 16, 4).putInt(width)
        ByteBuffer.wrap(png, 20, 4).putInt(height)
    }

    private companion object {
        val PNG_1X1: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        )
        val JPEG_1X1: ByteArray = Base64.getDecoder().decode(
            "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoMDAsKCwsNDhIQDQ4RDgsLEBYQERMUFRUVDA8XGBYUGBIUFRT/2wBDAQMEBAUEBQkFBQkUDQsNFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBT/wAARCAABAAEDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD9U6KKKAP/2Q==",
        )
    }
}

private class RecordingArtworkCache : ArtworkCache {
    val stored = mutableListOf<ByteArray>()

    override suspend fun store(
        sourceId: SourceId,
        sourceKey: String,
        mimeType: String,
        bytes: ByteArray,
    ): String {
        stored += bytes.copyOf()
        return "memory:$sourceKey"
    }

    override suspend fun remove(sourceId: SourceId, sourceKey: String) = Unit
    override suspend fun clearSource(sourceId: SourceId) = Unit
    override fun sourceUriPrefix(sourceId: SourceId): String? = null
}
