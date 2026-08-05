package com.cleartune.feature.player

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

data class LrcLine(val timestampMs: Long, val text: String)

class LrcParser(
    private val maximumBytes: Int = 256 * 1024,
    private val maximumLines: Int = 4_000,
) {
    init {
        require(maximumBytes > 0)
        require(maximumLines > 0)
    }

    fun parse(bytes: ByteArray): List<LrcLine> {
        if (bytes.isEmpty() || bytes.size > maximumBytes) return emptyList()
        val text = decode(bytes) ?: return emptyList()
        val offset = OFFSET.find(text)?.groupValues?.get(1)?.toLongOrNull()?.coerceIn(-60_000, 60_000) ?: 0
        return text.lineSequence().flatMap { rawLine ->
            val lyric = rawLine.replace(TIMESTAMP, "").trim()
            if (lyric.isEmpty()) return@flatMap emptySequence()
            TIMESTAMP.findAll(rawLine).mapNotNull { match ->
                val minutes = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val seconds = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
                if (seconds >= 60) return@mapNotNull null
                val fraction = match.groupValues[3]
                val fractionMs = when (fraction.length) {
                    0 -> 0
                    1 -> fraction.toLong() * 100
                    2 -> fraction.toLong() * 10
                    else -> fraction.take(3).padEnd(3, '0').toLong()
                }
                LrcLine(((minutes * 60 + seconds) * 1_000 + fractionMs + offset).coerceAtLeast(0), lyric)
            }
        }.sortedBy(LrcLine::timestampMs).take(maximumLines).toList()
    }

    private fun decode(bytes: ByteArray): String? {
        val payload = if (bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte()) {
            bytes.copyOfRange(3, bytes.size)
        } else bytes
        return runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(payload)).toString()
        }.getOrNull() ?: runCatching { String(payload, charset("GB18030")) }.getOrNull()
    }

    private companion object {
        val TIMESTAMP = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
        val OFFSET = Regex("(?im)^\\[offset:([+-]?\\d+)]")
    }
}
