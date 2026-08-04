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
) : WebDavMetadataEnricher {
    init {
        require(maximumHeadBytes in 1..MAXIMUM_METADATA_BYTES)
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
        val parsed = when (entry.name.substringAfterLast('.', "").lowercase()) {
            "mp3" -> parseId3(response.bytes)
            "flac" -> parseFlac(response.bytes)
            else -> null
        } ?: return fallback
        return parsed.copy(title = parsed.title.ifBlank { fallback.title })
    }

    private fun String?.isHeadRange(): Boolean = this?.startsWith("bytes 0-") == true

    private companion object {
        const val MAXIMUM_METADATA_BYTES = 4 * 1024 * 1024
    }
}

private fun parseId3(bytes: ByteArray): EnrichedTrackMetadata? {
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
    while (offset + 10 <= end) {
        val id = bytes.copyOfRange(offset, offset + 4).toString(Charsets.US_ASCII)
        if (id.all { it == '\u0000' }) break
        val size = if (version == 4) synchsafe(bytes, offset + 4) else int32(bytes, offset + 4)
        if (size <= 0 || offset + 10 + size > end) break
        val payload = bytes.copyOfRange(offset + 10, offset + 10 + size)
        when (id) {
            "TIT2" -> title = decodeId3Text(payload)
            "TALB" -> album = decodeId3Text(payload)
            "TPE1" -> decodeId3Text(payload)?.split('\u0000', ';')
                ?.map(String::trim)?.filter(String::isNotBlank)?.let(artists::addAll)
            "TLEN" -> duration = decodeId3Text(payload)?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
        }
        offset += 10 + size
    }
    return title?.takeIf(String::isNotBlank)?.let {
        EnrichedTrackMetadata(it, album?.takeIf(String::isNotBlank), artists.distinct(), duration)
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

private fun parseFlac(bytes: ByteArray): EnrichedTrackMetadata? {
    if (bytes.size < 8 || bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) != "fLaC") return null
    var offset = 4
    var title: String? = null
    var album: String? = null
    val artists = mutableListOf<String>()
    var durationMs: Long? = null
    while (offset + 4 <= bytes.size) {
        val header = bytes[offset].toInt() and 0xff
        val last = header and 0x80 != 0
        val type = header and 0x7f
        val length = ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)
        val start = offset + 4
        val end = start + length
        if (length < 0 || end > bytes.size) break
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
        }
        offset = end
        if (last) break
    }
    return title?.takeIf(String::isNotBlank)?.let {
        EnrichedTrackMetadata(it, album?.takeIf(String::isNotBlank), artists.distinct(), durationMs)
    }
}

private fun parseVorbisComments(bytes: ByteArray, start: Int, end: Int): List<Pair<String, String>> {
    var offset = start
    fun readLength(): Int? {
        if (offset + 4 > end) return null
        val value = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
        offset += 4
        return value.takeIf { it >= 0 }
    }
    val vendorLength = readLength() ?: return emptyList()
    if (offset + vendorLength > end) return emptyList()
    offset += vendorLength
    val count = readLength()?.coerceAtMost(1_024) ?: return emptyList()
    return buildList {
        repeat(count) {
            val length = readLength() ?: return@buildList
            if (offset + length > end) return@buildList
            val comment = bytes.copyOfRange(offset, offset + length).toString(Charsets.UTF_8)
            offset += length
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
