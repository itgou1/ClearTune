package com.cleartune.data.webdav

import com.cleartune.core.model.MusicSource
import com.cleartune.core.model.SourceId
import com.cleartune.core.model.SourceType
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
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
    fun `MP3 APIC artwork is bounded and persisted as a contained local reference`() = runTest {
        val root = Files.createTempDirectory("mp3-artwork-").toFile()
        try {
            val artwork = byteArrayOf(1, 2, 3, 4)
            val fixture = id3(
                "TIT2" to text("Artwork Song"),
                "APIC" to (byteArrayOf(3) + "image/jpeg".toByteArray() +
                    byteArrayOf(0, 3, 0) + artwork),
            )
            val enricher = RangeWebDavMetadataEnricher(
                reader = WebDavRangeReader { _, _, _, _, _ ->
                    RangeResponse(fixture, "bytes 0-${fixture.lastIndex}/${fixture.size}", true, null)
                },
                artworkCache = EmbeddedArtworkCache(root, maximumArtworkBytes = 8),
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
    fun `FLAC PICTURE artwork is parsed and malicious data length is rejected`() = runTest {
        val root = Files.createTempDirectory("flac-artwork-").toFile()
        try {
            val pictureBytes = byteArrayOf(9, 8, 7)
            val valid = flacWithPicture("Picture Song", "image/png", pictureBytes, pictureBytes.size)
            val malicious = flacWithPicture("Broken Picture", "image/png", byteArrayOf(1), Int.MAX_VALUE)
            val responses = ArrayDeque(listOf(valid, malicious))
            val enricher = RangeWebDavMetadataEnricher(
                reader = WebDavRangeReader { _, _, _, _, _ ->
                    val bytes = responses.removeFirst()
                    RangeResponse(bytes, "bytes 0-${bytes.lastIndex}/${bytes.size}", true, null)
                },
                artworkCache = EmbeddedArtworkCache(root, maximumArtworkBytes = 8),
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

    private fun source() = MusicSource(
        SourceId("source"), "Remote", SourceType.WEBDAV, "https://music.example/dav/",
    )

    private fun entry(name: String, size: Long) = WebDavEntry(
        "https://music.example/dav/$name".replace(" ", "%20").toHttpUrl(), name, false, size, null,
    )

    private fun text(value: String) = byteArrayOf(3) + value.toByteArray(Charsets.UTF_8)

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
            repeat(4) { write(ByteBuffer.allocate(4).putInt(1).array()) }
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
}
