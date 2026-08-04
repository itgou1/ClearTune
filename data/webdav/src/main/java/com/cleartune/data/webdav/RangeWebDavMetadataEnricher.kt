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
    val mimeType = payload.copyOfRange(1, mimeEnd).toString(Charsets.ISO_8859_1).lowercase()
    if (mimeType !in ALLOWED_ARTWORK_TYPES) return null
    val descriptionStart = mimeEnd + 2
    if (descriptionStart > payload.size) return null
    val imageStart = when (encoding) {
        0, 3 -> payload.indexOfByte(0, descriptionStart).takeIf { it >= 0 }?.plus(1)
        1, 2 -> findDoubleZero(payload, descriptionStart)
        else -> null
    } ?: return null
    val imageLength = payload.size - imageStart
    if (imageLength !in 1..maximumArtworkBytes) return null
    return EmbeddedArtwork(mimeType, payload.copyOfRange(imageStart, payload.size))
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
    val mimeBytes = cursor.readBytes(mimeLength) ?: return null
    val mimeType = mimeBytes.toString(Charsets.US_ASCII).lowercase()
    if (mimeType !in ALLOWED_ARTWORK_TYPES) return null
    val descriptionLength = cursor.readUnsignedInt() ?: return null
    if (!cursor.skip(descriptionLength)) return null
    val width = cursor.readUnsignedInt() ?: return null
    val height = cursor.readUnsignedInt() ?: return null
    cursor.readUnsignedInt() ?: return null // color depth
    cursor.readUnsignedInt() ?: return null // indexed colors
    if (width !in 1..MAXIMUM_ARTWORK_DIMENSION || height !in 1..MAXIMUM_ARTWORK_DIMENSION) return null
    val imageLength = cursor.readUnsignedInt() ?: return null
    if (imageLength !in 1..maximumArtworkBytes.toLong()) return null
    val image = cursor.readBytes(imageLength) ?: return null
    return EmbeddedArtwork(mimeType, image)
}

private class BigEndianCursor(
    private val bytes: ByteArray,
    start: Int,
    private val end: Int,
) {
    private var offset = start

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

private val ALLOWED_ARTWORK_TYPES = setOf("image/jpeg", "image/png")
private const val MAXIMUM_ARTWORK_DIMENSION = 16_384L

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
