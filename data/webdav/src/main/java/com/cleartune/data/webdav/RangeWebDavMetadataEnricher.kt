package com.cleartune.data.webdav

import com.cleartune.core.model.MusicSource
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlinx.coroutines.CancellationException

fun interface WebDavRangeReader {
    suspend fun read(
        source: MusicSource,
        entry: WebDavEntry,
        start: Long,
        endInclusive: Long,
        maxBytes: Int,
    ): RangeResponse
}

class RangeWebDavMetadataEnricher(
    private val reader: WebDavRangeReader,
    private val maximumHeadBytes: Int = 256 * 1024,
    private val maximumArtworkBytes: Int = 512 * 1024,
    private val artworkCache: ArtworkCache = ArtworkCache.None,
) : WebDavMetadataEnricher {
    init {
        require(maximumHeadBytes in 1..MAXIMUM_METADATA_BYTES)
        require(maximumArtworkBytes in 1..MAXIMUM_METADATA_BYTES)
    }

    override suspend fun enrich(source: MusicSource, entry: WebDavEntry): EnrichedTrackMetadata {
        val fallback = EnrichedTrackMetadata(entry.name.substringBeforeLast('.', entry.name))
        val requestedBytes = min(entry.sizeBytes ?: maximumHeadBytes.toLong(), maximumHeadBytes.toLong())
            .toInt()
        if (requestedBytes <= 0) return fallback
        val response = try {
            reader.read(source, entry, 0, requestedBytes - 1L, requestedBytes)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return fallback
        }
        if (!response.rangeHonored || !response.contentRange.isHeadRange()) return fallback
        val parsed = try {
            when (entry.name.substringAfterLast('.', "").lowercase()) {
                "mp3" -> parseId3(response.bytes, maximumArtworkBytes)
                "flac" -> parseFlac(response.bytes, maximumArtworkBytes)
                else -> null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return fallback
        val sourceKey = entry.href.encodedPath
        val artworkRef = parsed.artwork?.let { artwork ->
            artworkCache.store(source.id, sourceKey, artwork.mimeType, artwork.bytes)
        } ?: run {
            artworkCache.remove(source.id, sourceKey)
            null
        }
        return EnrichedTrackMetadata(
            title = parsed.title.ifBlank { fallback.title },
            albumTitle = parsed.albumTitle,
            artistNames = parsed.artistNames,
            durationMs = parsed.durationMs,
            artworkRef = artworkRef,
            artworkResolved = true,
        )
    }

    private fun String?.isHeadRange(): Boolean = this?.startsWith("bytes 0-") == true

    private companion object {
        const val MAXIMUM_METADATA_BYTES = 4 * 1024 * 1024
    }
}

private fun parseId3(bytes: ByteArray, maximumArtworkBytes: Int): ParsedTrackMetadata? {
    if (bytes.size < 10 || bytes.copyOfRange(0, 3).toString(Charsets.US_ASCII) != "ID3") return null
    val version = bytes[3].toInt() and 0xff
    if (version !in 3..4) return null
    val tagSize = synchsafe(bytes, 6)
    val end = min(bytes.size, 10 + tagSize)
    var offset = 10
    var title: String? = null
    var album: String? = null
    val artists = mutableListOf<String>()
    var duration: Long? = null
    var artwork: EmbeddedArtwork? = null
    while (end - offset >= 10) {
        val id = bytes.copyOfRange(offset, offset + 4).toString(Charsets.US_ASCII)
        if (id.all { it == '\u0000' }) break
        val size = if (version == 4) synchsafe(bytes, offset + 4) else int32(bytes, offset + 4)
        val payloadStart = offset + 10
        if (size <= 0 || size > end - payloadStart) break
        val payloadEnd = payloadStart + size
        val payload = bytes.copyOfRange(payloadStart, payloadEnd)
        when (id) {
            "TIT2" -> title = decodeId3Text(payload)
            "TALB" -> album = decodeId3Text(payload)
            "TPE1" -> decodeId3Text(payload)?.split('\u0000', ';')
                ?.map(String::trim)?.filter(String::isNotBlank)?.let(artists::addAll)
            "TLEN" -> duration = decodeId3Text(payload)?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
            "APIC" -> artwork = parseId3Picture(payload, maximumArtworkBytes) ?: artwork
        }
        offset = payloadEnd
    }
    return title?.takeIf(String::isNotBlank)?.let {
        ParsedTrackMetadata(it, album?.takeIf(String::isNotBlank), artists.distinct(), duration, artwork)
    }
}

private fun decodeId3Text(payload: ByteArray): String? {
    if (payload.isEmpty()) return null
    val body = payload.copyOfRange(1, payload.size)
    val decoded = when (payload[0].toInt() and 0xff) {
        0 -> body.toString(Charsets.ISO_8859_1)
        1 -> body.toString(Charsets.UTF_16)
        2 -> body.toString(Charsets.UTF_16BE)
        3 -> body.toString(Charsets.UTF_8)
        else -> return null
    }
    return decoded.trim('\u0000', ' ', '\r', '\n', '\t')
}

private fun parseFlac(bytes: ByteArray, maximumArtworkBytes: Int): ParsedTrackMetadata? {
    if (bytes.size < 8 || bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) != "fLaC") return null
    var offset = 4
    var title: String? = null
    var album: String? = null
    val artists = mutableListOf<String>()
    var durationMs: Long? = null
    var artwork: EmbeddedArtwork? = null
    while (bytes.size - offset >= 4) {
        val header = bytes[offset].toInt() and 0xff
        val last = header and 0x80 != 0
        val type = header and 0x7f
        val length = ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)
        val start = offset + 4
        if (length > bytes.size - start) break
        val end = start + length
        when (type) {
            0 -> if (length >= 18) {
                var packed = 0L
                repeat(8) { index -> packed = (packed shl 8) or (bytes[start + 10 + index].toLong() and 0xff) }
                val sampleRate = (packed ushr 44) and 0xfffff
                val totalSamples = packed and 0xfffffffffL
                if (sampleRate > 0) durationMs = totalSamples * 1_000L / sampleRate
            }
            4 -> parseVorbisComments(bytes, start, end).forEach { (key, value) ->
                when (key.uppercase()) {
                    "TITLE" -> title = value
                    "ALBUM" -> album = value
                    "ARTIST" -> artists += value
                }
            }
            6 -> artwork = parseFlacPicture(bytes, start, end, maximumArtworkBytes) ?: artwork
        }
        offset = end
        if (last) break
    }
    return title?.takeIf(String::isNotBlank)?.let {
        ParsedTrackMetadata(it, album?.takeIf(String::isNotBlank), artists.distinct(), durationMs, artwork)
    }
}

private fun parseId3Picture(payload: ByteArray, maximumArtworkBytes: Int): EmbeddedArtwork? {
    if (payload.size < 5) return null
    val encoding = payload[0].toInt() and 0xff
    val mimeEnd = payload.indexOfByte(0, 1).takeIf { it >= 0 } ?: return null
    if (mimeEnd - 1 !in 1..MAXIMUM_ARTWORK_MIME_BYTES.toInt()) return null
    val mimeType = payload.copyOfRange(1, mimeEnd).toString(Charsets.ISO_8859_1)
    val descriptionStart = mimeEnd + 2
    if (descriptionStart > payload.size) return null
    val imageStart = when (encoding) {
        0, 3 -> payload.indexOfByte(0, descriptionStart).takeIf { it >= 0 }?.plus(1)
        1, 2 -> findDoubleZero(payload, descriptionStart)
        else -> null
    } ?: return null
    return validateEmbeddedArtwork(
        declaredMimeType = mimeType,
        bytes = payload,
        start = imageStart,
        end = payload.size,
        maximumArtworkBytes = maximumArtworkBytes,
    )
}

private fun ByteArray.indexOfByte(value: Byte, start: Int): Int {
    for (index in start.coerceAtLeast(0) until size) if (this[index] == value) return index
    return -1
}

private fun findDoubleZero(bytes: ByteArray, start: Int): Int? {
    var offset = start
    while (bytes.size - offset >= 2) {
        if (bytes[offset] == 0.toByte() && bytes[offset + 1] == 0.toByte()) return offset + 2
        offset += 2
    }
    return null
}

private fun parseFlacPicture(
    bytes: ByteArray,
    start: Int,
    end: Int,
    maximumArtworkBytes: Int,
): EmbeddedArtwork? {
    val cursor = BigEndianCursor(bytes, start, end)
    cursor.readUnsignedInt() ?: return null // picture type
    val mimeLength = cursor.readUnsignedInt() ?: return null
    if (mimeLength !in 1..MAXIMUM_ARTWORK_MIME_BYTES) return null
    val mimeBytes = cursor.readBytes(mimeLength) ?: return null
    val mimeType = mimeBytes.toString(Charsets.US_ASCII)
    val descriptionLength = cursor.readUnsignedInt() ?: return null
    if (!cursor.skip(descriptionLength)) return null
    val width = cursor.readUnsignedInt() ?: return null
    val height = cursor.readUnsignedInt() ?: return null
    cursor.readUnsignedInt() ?: return null // color depth
    cursor.readUnsignedInt() ?: return null // indexed colors
    val imageLength = cursor.readUnsignedInt() ?: return null
    if (imageLength !in 1..maximumArtworkBytes.toLong()) return null
    val imageStart = cursor.position
    if (!cursor.skip(imageLength)) return null
    if (cursor.position != end) return null
    return validateEmbeddedArtwork(
        declaredMimeType = mimeType,
        bytes = bytes,
        start = imageStart,
        end = cursor.position,
        maximumArtworkBytes = maximumArtworkBytes,
        declaredWidth = width,
        declaredHeight = height,
    )
}

private class BigEndianCursor(
    private val bytes: ByteArray,
    start: Int,
    private val end: Int,
) {
    private var offset = start
    val position: Int get() = offset

    fun readUnsignedInt(): Long? {
        if (offset < 0 || end - offset < 4) return null
        var value = 0L
        repeat(4) { value = (value shl 8) or (bytes[offset++].toLong() and 0xff) }
        return value
    }

    fun skip(length: Long): Boolean {
        if (length < 0 || length > (end - offset).toLong()) return false
        offset += length.toInt()
        return true
    }

    fun readBytes(length: Long): ByteArray? {
        if (length < 0 || length > (end - offset).toLong() || length > Int.MAX_VALUE) return null
        val next = offset + length.toInt()
        return bytes.copyOfRange(offset, next).also { offset = next }
    }
}

private data class ParsedTrackMetadata(
    val title: String,
    val albumTitle: String?,
    val artistNames: List<String>,
    val durationMs: Long?,
    val artwork: EmbeddedArtwork?,
)

private data class EmbeddedArtwork(val mimeType: String, val bytes: ByteArray)

private data class SniffedArtwork(
    val mimeType: String,
    val width: Long,
    val height: Long,
)

private fun validateEmbeddedArtwork(
    declaredMimeType: String,
    bytes: ByteArray,
    start: Int,
    end: Int,
    maximumArtworkBytes: Int,
    declaredWidth: Long? = null,
    declaredHeight: Long? = null,
): EmbeddedArtwork? {
    if (start < 0 || end < start || end > bytes.size) return null
    val byteCount = end - start
    if (byteCount !in 1..maximumArtworkBytes) return null
    val declaredMime = canonicalArtworkMime(declaredMimeType) ?: return null
    val sniffed = sniffArtwork(bytes, start, end) ?: return null
    if (sniffed.mimeType != declaredMime) return null
    if (!validArtworkDimensions(sniffed.width, sniffed.height)) return null
    if (declaredWidth != null && declaredWidth != sniffed.width) return null
    if (declaredHeight != null && declaredHeight != sniffed.height) return null
    return EmbeddedArtwork(sniffed.mimeType, bytes.copyOfRange(start, end))
}

private fun canonicalArtworkMime(value: String): String? = when (value.trim().lowercase()) {
    "image/jpeg", "image/jpg" -> "image/jpeg"
    "image/png" -> "image/png"
    else -> null
}

private fun sniffArtwork(bytes: ByteArray, start: Int, end: Int): SniffedArtwork? = when {
    matches(bytes, start, end, PNG_SIGNATURE) -> parsePngHeader(bytes, start, end)
    end - start >= 2 && unsigned(bytes[start]) == 0xff && unsigned(bytes[start + 1]) == 0xd8 ->
        parseJpegHeader(bytes, start, end)
    else -> null
}

private fun parsePngHeader(bytes: ByteArray, start: Int, end: Int): SniffedArtwork? {
    if (end - start < PNG_MINIMUM_BYTES) return null
    var cursor = start + PNG_SIGNATURE.size
    var dimensions: Pair<Long, Long>? = null
    var sawImageData = false
    while (end - cursor >= PNG_CHUNK_OVERHEAD) {
        val dataLength = unsignedInt32(bytes, cursor)
        if (dataLength > Int.MAX_VALUE || dataLength > (end - cursor - PNG_CHUNK_OVERHEAD).toLong()) {
            return null
        }
        val dataStart = cursor + 8
        val dataEnd = dataStart + dataLength.toInt()
        when {
            matchesAscii(bytes, cursor + 4, "IHDR") -> {
                if (dimensions != null || cursor != start + PNG_SIGNATURE.size || dataLength != 13L) return null
                dimensions = unsignedInt32(bytes, dataStart) to unsignedInt32(bytes, dataStart + 4)
                val bitDepth = unsigned(bytes[dataStart + 8])
                val colorType = unsigned(bytes[dataStart + 9])
                if (!validPngColorFormat(bitDepth, colorType)) return null
                if (unsigned(bytes[dataStart + 10]) != 0 || unsigned(bytes[dataStart + 11]) != 0 ||
                    unsigned(bytes[dataStart + 12]) !in 0..1
                ) return null
            }
            matchesAscii(bytes, cursor + 4, "IDAT") -> {
                if (dimensions == null) return null
                sawImageData = true
            }
            matchesAscii(bytes, cursor + 4, "IEND") -> {
                if (dataLength != 0L || !sawImageData || dataEnd + 4 != end) return null
                val (width, height) = dimensions ?: return null
                return SniffedArtwork("image/png", width, height)
            }
        }
        cursor = dataEnd + 4 // include the chunk CRC without allocating or decoding it
    }
    return null
}

private fun validPngColorFormat(bitDepth: Int, colorType: Int): Boolean = when (colorType) {
    0 -> bitDepth == 1 || bitDepth == 2 || bitDepth == 4 || bitDepth == 8 || bitDepth == 16
    2 -> bitDepth == 8 || bitDepth == 16
    3 -> bitDepth == 1 || bitDepth == 2 || bitDepth == 4 || bitDepth == 8
    4, 6 -> bitDepth == 8 || bitDepth == 16
    else -> false
}

private fun parseJpegHeader(bytes: ByteArray, start: Int, end: Int): SniffedArtwork? {
    if (end - start < JPEG_MINIMUM_BYTES ||
        unsigned(bytes[end - 2]) != 0xff || unsigned(bytes[end - 1]) != 0xd9
    ) return null
    var cursor = start + 2
    var segmentCount = 0
    while (cursor < end && segmentCount++ < MAXIMUM_JPEG_SEGMENTS) {
        if (unsigned(bytes[cursor]) != 0xff) return null
        while (cursor < end && unsigned(bytes[cursor]) == 0xff) cursor += 1
        if (cursor >= end) return null
        val marker = unsigned(bytes[cursor++])
        if (marker == 0x00 || marker == 0xd9 || marker == 0xda) return null
        if (marker == 0x01 || marker == 0xd8 || marker in 0xd0..0xd7) continue
        if (end - cursor < 2) return null
        val segmentLength = unsignedInt16(bytes, cursor)
        if (segmentLength < 2 || segmentLength > end - cursor) return null
        if (marker in JPEG_START_OF_FRAME_MARKERS) {
            if (segmentLength < 8) return null
            val height = unsignedInt16(bytes, cursor + 3).toLong()
            val width = unsignedInt16(bytes, cursor + 5).toLong()
            return SniffedArtwork("image/jpeg", width, height)
        }
        cursor += segmentLength
    }
    return null
}

private fun validArtworkDimensions(width: Long, height: Long): Boolean {
    if (width !in 1..MAXIMUM_ARTWORK_DIMENSION || height !in 1..MAXIMUM_ARTWORK_DIMENSION) {
        return false
    }
    return width <= MAXIMUM_ARTWORK_PIXELS / height
}

private fun matches(bytes: ByteArray, start: Int, end: Int, expected: IntArray): Boolean {
    if (start < 0 || expected.size > end - start) return false
    return expected.indices.all { index -> unsigned(bytes[start + index]) == expected[index] }
}

private fun matchesAscii(bytes: ByteArray, start: Int, expected: String): Boolean {
    if (start < 0 || expected.length > bytes.size - start) return false
    return expected.indices.all { index -> unsigned(bytes[start + index]) == expected[index].code }
}

private fun unsigned(value: Byte): Int = value.toInt() and 0xff

private fun unsignedInt16(bytes: ByteArray, offset: Int): Int =
    (unsigned(bytes[offset]) shl 8) or unsigned(bytes[offset + 1])

private fun unsignedInt32(bytes: ByteArray, offset: Int): Long =
    (unsigned(bytes[offset]).toLong() shl 24) or
        (unsigned(bytes[offset + 1]).toLong() shl 16) or
        (unsigned(bytes[offset + 2]).toLong() shl 8) or
        unsigned(bytes[offset + 3]).toLong()

private val PNG_SIGNATURE = intArrayOf(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
private val JPEG_START_OF_FRAME_MARKERS = setOf(
    0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf,
)
private const val PNG_CHUNK_OVERHEAD = 12
private const val PNG_MINIMUM_BYTES = 57
private const val JPEG_MINIMUM_BYTES = 12
private const val MAXIMUM_JPEG_SEGMENTS = 1_024
private const val MAXIMUM_ARTWORK_MIME_BYTES = 64L
private const val MAXIMUM_ARTWORK_DIMENSION = 8_192L
private const val MAXIMUM_ARTWORK_PIXELS = 32_000_000L

private fun parseVorbisComments(bytes: ByteArray, start: Int, end: Int): List<Pair<String, String>> {
    var offset = start
    fun readLength(): Int? {
        if (offset < start || end - offset < 4) return null
        val value = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
        offset += 4
        return value.takeIf { it >= 0 }
    }
    val vendorLength = readLength() ?: return emptyList()
    if (vendorLength > end - offset) return emptyList()
    offset += vendorLength
    val count = readLength()?.coerceAtMost(1_024) ?: return emptyList()
    return buildList {
        repeat(count) {
            val length = readLength() ?: return@buildList
            if (length > end - offset) return@buildList
            val commentEnd = offset + length
            val comment = bytes.copyOfRange(offset, commentEnd).toString(Charsets.UTF_8)
            offset = commentEnd
            val separator = comment.indexOf('=')
            if (separator > 0) add(comment.substring(0, separator) to comment.substring(separator + 1))
        }
    }
}

private fun synchsafe(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0x7f) shl 21) or
        ((bytes[offset + 1].toInt() and 0x7f) shl 14) or
        ((bytes[offset + 2].toInt() and 0x7f) shl 7) or
        (bytes[offset + 3].toInt() and 0x7f)

private fun int32(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xff) shl 24) or
        ((bytes[offset + 1].toInt() and 0xff) shl 16) or
        ((bytes[offset + 2].toInt() and 0xff) shl 8) or
        (bytes[offset + 3].toInt() and 0xff)
