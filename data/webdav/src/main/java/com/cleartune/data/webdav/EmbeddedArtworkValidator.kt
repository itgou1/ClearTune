package com.cleartune.data.webdav

import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

internal data class EmbeddedArtwork(val mimeType: String, val bytes: ByteArray)

fun interface JpegDecodeProbe {
    fun canDecode(
        bytes: ByteArray,
        start: Int,
        end: Int,
        expectedWidth: Int,
        expectedHeight: Int,
    ): Boolean
}

private data class SniffedArtwork(
    val mimeType: String,
    val width: Long,
    val height: Long,
)

/**
 * Structurally validates bounded embedded artwork. JPEGs also have to survive a tightly sampled
 * platform decode before their compressed bytes may reach the cache. Unsupported or malformed
 * images return null so track metadata can continue through the normal no-artwork fallback.
 */
internal fun validateEmbeddedArtwork(
    declaredMimeType: String,
    bytes: ByteArray,
    start: Int,
    end: Int,
    maximumArtworkBytes: Int,
    jpegDecodeProbe: JpegDecodeProbe,
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
    if (sniffed.mimeType == "image/jpeg") {
        if (byteCount > MAXIMUM_JPEG_DECODE_BYTES) return null
        val decodable = try {
            jpegDecodeProbe.canDecode(
                bytes = bytes,
                start = start,
                end = end,
                expectedWidth = sniffed.width.toInt(),
                expectedHeight = sniffed.height.toInt(),
            )
        } catch (_: Exception) {
            false
        }
        if (!decodable) return null
    }
    return EmbeddedArtwork(sniffed.mimeType, bytes.copyOfRange(start, end))
}

private fun canonicalArtworkMime(value: String): String? = when (value.trim().lowercase()) {
    "image/jpeg", "image/jpg" -> "image/jpeg"
    "image/png" -> "image/png"
    else -> null
}

private fun sniffArtwork(bytes: ByteArray, start: Int, end: Int): SniffedArtwork? = when {
    matches(bytes, start, end, PNG_SIGNATURE) -> parsePng(bytes, start, end)
    end - start >= 2 && unsigned(bytes[start]) == 0xff && unsigned(bytes[start + 1]) == 0xd8 ->
        parseJpeg(bytes, start, end)
    else -> null
}

private data class PngHeader(
    val width: Long,
    val height: Long,
    val bitDepth: Int,
    val colorType: Int,
    val interlaceMethod: Int,
) {
    val channelCount: Int
        get() = when (colorType) {
            0, 3 -> 1
            2 -> 3
            4 -> 2
            6 -> 4
            else -> error("validated color type")
        }
}

private fun parsePng(bytes: ByteArray, start: Int, end: Int): SniffedArtwork? {
    if (end - start < PNG_MINIMUM_BYTES) return null
    var cursor = start + PNG_SIGNATURE.size
    var header: PngHeader? = null
    var paletteEntries: Int? = null
    var sawImageData = false
    var imageDataEnded = false
    var chunkCount = 0
    var imageDataValidator: PngImageDataValidator? = null
    try {
        while (end - cursor >= PNG_CHUNK_OVERHEAD && chunkCount++ < MAXIMUM_PNG_CHUNKS) {
            val dataLength = unsignedInt32(bytes, cursor)
            if (dataLength > Int.MAX_VALUE || dataLength > (end - cursor - PNG_CHUNK_OVERHEAD).toLong()) {
                return null
            }
            val typeOffset = cursor + 4
            if (!isPngChunkType(bytes, typeOffset)) return null
            val type = ascii(bytes, typeOffset, 4)
            val dataStart = cursor + 8
            val dataEnd = dataStart + dataLength.toInt()
            if (!validPngCrc(bytes, typeOffset, dataEnd, dataEnd)) return null

            when (type) {
                "IHDR" -> {
                    if (header != null || cursor != start + PNG_SIGNATURE.size || dataLength != 13L) return null
                    val width = unsignedInt32(bytes, dataStart)
                    val height = unsignedInt32(bytes, dataStart + 4)
                    val bitDepth = unsigned(bytes[dataStart + 8])
                    val colorType = unsigned(bytes[dataStart + 9])
                    val interlaceMethod = unsigned(bytes[dataStart + 12])
                    if (!validArtworkDimensions(width, height) || !validPngColorFormat(bitDepth, colorType)) {
                        return null
                    }
                    if (unsigned(bytes[dataStart + 10]) != 0 || unsigned(bytes[dataStart + 11]) != 0 ||
                        interlaceMethod !in 0..1
                    ) return null
                    header = PngHeader(width, height, bitDepth, colorType, interlaceMethod)
                    imageDataValidator = PngImageDataValidator.create(header) ?: return null
                }

                "PLTE" -> {
                    val currentHeader = header ?: return null
                    if (paletteEntries != null || sawImageData || currentHeader.colorType in setOf(0, 4)) return null
                    if (dataLength !in 3L..768L || dataLength % 3L != 0L) return null
                    val entries = dataLength.toInt() / 3
                    if (currentHeader.colorType == 3 && entries > (1 shl currentHeader.bitDepth)) return null
                    paletteEntries = entries
                }

                "IDAT" -> {
                    val currentHeader = header ?: return null
                    if (imageDataEnded || currentHeader.colorType == 3 && paletteEntries == null) return null
                    sawImageData = true
                    if (!imageDataValidator!!.consume(bytes, dataStart, dataLength.toInt())) return null
                }

                "IEND" -> {
                    val currentHeader = header ?: return null
                    if (dataLength != 0L || !sawImageData || dataEnd + PNG_CRC_BYTES != end) return null
                    if (currentHeader.colorType == 3 && paletteEntries == null) return null
                    if (!imageDataValidator!!.finish()) return null
                    return SniffedArtwork("image/png", currentHeader.width, currentHeader.height)
                }

                else -> {
                    if (header == null || isCriticalPngChunk(bytes[typeOffset])) return null
                    if (sawImageData) imageDataEnded = true
                }
            }
            cursor = dataEnd + PNG_CRC_BYTES
        }
    } finally {
        imageDataValidator?.close()
    }
    return null
}

private class PngImageDataValidator private constructor(
    private val inflater: Inflater,
    private val scanlines: PngScanlines,
) {
    private val output = ByteArray(PNG_INFLATE_BUFFER_BYTES)
    private var producedBytes = 0L

    fun consume(bytes: ByteArray, start: Int, length: Int): Boolean {
        if (length == 0) return true
        if (inflater.finished() || !inflater.needsInput()) return false
        inflater.setInput(bytes, start, length)
        return drain() && (!inflater.finished() || inflater.remaining == 0)
    }

    fun finish(): Boolean = drain() && inflater.finished() && inflater.remaining == 0 &&
        producedBytes == scanlines.expectedBytes && scanlines.complete

    fun close() = inflater.end()

    private fun drain(): Boolean = try {
        while (!inflater.needsInput() && !inflater.finished()) {
            val count = inflater.inflate(output)
            if (count == 0) {
                if (inflater.needsDictionary() || !inflater.needsInput()) return false
                break
            }
            if (producedBytes > scanlines.expectedBytes - count) return false
            for (index in 0 until count) {
                if (!scanlines.accept(unsigned(output[index]))) return false
            }
            producedBytes += count
        }
        !inflater.needsDictionary()
    } catch (_: DataFormatException) {
        false
    }

    companion object {
        fun create(header: PngHeader): PngImageDataValidator? {
            val scanlines = PngScanlines.create(header) ?: return null
            return PngImageDataValidator(Inflater(), scanlines)
        }
    }
}

private class PngScanlines private constructor(
    private val rows: List<PngRows>,
    val expectedBytes: Long,
) {
    private var passIndex = 0
    private var rowIndex = 0L
    private var byteInRow = 0L

    val complete: Boolean
        get() = passIndex == rows.size

    fun accept(value: Int): Boolean {
        if (complete) return false
        val pass = rows[passIndex]
        if (byteInRow == 0L && value !in 0..4) return false
        byteInRow += 1
        if (byteInRow == pass.bytesPerRow) {
            byteInRow = 0
            rowIndex += 1
            if (rowIndex == pass.rowCount) {
                rowIndex = 0
                passIndex += 1
            }
        }
        return true
    }

    companion object {
        fun create(header: PngHeader): PngScanlines? {
            val bitsPerPixel = header.channelCount.toLong() * header.bitDepth
            val passes = if (header.interlaceMethod == 0) {
                listOf(PngPass(0, 0, 1, 1))
            } else {
                ADAM7_PASSES
            }
            val rows = mutableListOf<PngRows>()
            var expectedBytes = 0L
            for (pass in passes) {
                val passWidth = pass.extent(header.width, pass.xStart, pass.xStep)
                val passHeight = pass.extent(header.height, pass.yStart, pass.yStep)
                if (passWidth == 0L || passHeight == 0L) continue
                val rowBits = safeMultiply(passWidth, bitsPerPixel) ?: return null
                val bytesPerRow = (rowBits + 7L) / 8L + 1L
                val passBytes = safeMultiply(bytesPerRow, passHeight) ?: return null
                if (expectedBytes > MAXIMUM_PNG_INFLATED_BYTES - passBytes) return null
                expectedBytes += passBytes
                rows += PngRows(passHeight, bytesPerRow)
            }
            if (rows.isEmpty() || expectedBytes !in 1..MAXIMUM_PNG_INFLATED_BYTES) return null
            return PngScanlines(rows, expectedBytes)
        }
    }
}

private data class PngRows(val rowCount: Long, val bytesPerRow: Long)

private data class PngPass(val xStart: Long, val yStart: Long, val xStep: Long, val yStep: Long) {
    fun extent(size: Long, start: Long, step: Long): Long =
        if (size <= start) 0 else (size - start + step - 1L) / step
}

private data class JpegFrame(
    val marker: Int,
    val width: Long,
    val height: Long,
    val components: Map<Int, JpegComponent>,
)

private data class JpegComponent(val quantizationTable: Int)

private data class JpegScan(
    val componentIds: Set<Int>,
    val nextMarkerOffset: Int,
)

private fun parseJpeg(bytes: ByteArray, start: Int, end: Int): SniffedArtwork? {
    if (end - start < JPEG_MINIMUM_BYTES) return null
    var cursor = start + 2
    var segmentCount = 0
    var frame: JpegFrame? = null
    val quantizationTables = BooleanArray(4)
    val huffmanTables = Array(2) { BooleanArray(4) }
    val scannedComponents = mutableSetOf<Int>()
    var restartInterval = 0
    var scanCount = 0

    while (cursor < end && segmentCount++ < MAXIMUM_JPEG_SEGMENTS) {
        if (unsigned(bytes[cursor]) != 0xff) return null
        while (cursor < end && unsigned(bytes[cursor]) == 0xff) cursor += 1
        if (cursor >= end) return null
        val marker = unsigned(bytes[cursor++])
        if (marker == JPEG_EOI) {
            val currentFrame = frame ?: return null
            if (cursor != end || scanCount == 0 || !scannedComponents.containsAll(currentFrame.components.keys)) {
                return null
            }
            return SniffedArtwork("image/jpeg", currentFrame.width, currentFrame.height)
        }
        if (marker == 0x00 || marker == JPEG_SOI || marker == JPEG_TEM || marker in JPEG_RESTART_MARKERS) {
            return null
        }
        if (end - cursor < 2) return null
        val segmentLength = unsignedInt16(bytes, cursor)
        if (segmentLength < 2 || segmentLength > end - cursor) return null
        val dataStart = cursor + 2
        val dataEnd = cursor + segmentLength

        when (marker) {
            JPEG_DQT -> if (!parseJpegQuantizationTables(bytes, dataStart, dataEnd, quantizationTables)) return null
            JPEG_DHT -> if (!parseJpegHuffmanTables(bytes, dataStart, dataEnd, huffmanTables)) return null
            JPEG_SOF_BASELINE, JPEG_SOF_PROGRESSIVE -> {
                if (frame != null) return null
                frame = parseJpegFrame(bytes, marker, dataStart, dataEnd) ?: return null
            }
            JPEG_DRI -> {
                if (segmentLength != 4) return null
                restartInterval = unsignedInt16(bytes, dataStart)
            }
            JPEG_SOS -> {
                val currentFrame = frame ?: return null
                if (currentFrame.components.values.any { !quantizationTables[it.quantizationTable] }) return null
                val componentIds = parseJpegScanHeader(
                    bytes,
                    dataStart,
                    dataEnd,
                    currentFrame,
                    huffmanTables,
                ) ?: return null
                val scan = scanJpegEntropy(bytes, dataEnd, end, restartInterval, componentIds) ?: return null
                scannedComponents += scan.componentIds
                scanCount += 1
                cursor = scan.nextMarkerOffset
                continue
            }
            in JPEG_APP_MARKERS, JPEG_COM -> Unit
            else -> return null
        }
        cursor = dataEnd
    }
    return null
}

private fun parseJpegQuantizationTables(
    bytes: ByteArray,
    start: Int,
    end: Int,
    present: BooleanArray,
): Boolean {
    var cursor = start
    if (cursor == end) return false
    while (cursor < end) {
        val definition = unsigned(bytes[cursor++])
        val precision = definition ushr 4
        val table = definition and 0x0f
        if (precision !in 0..1 || table !in present.indices) return false
        val valueBytes = if (precision == 0) 1 else 2
        val tableBytes = 64 * valueBytes
        if (tableBytes > end - cursor) return false
        var valueOffset = cursor
        repeat(64) {
            val value = if (valueBytes == 1) unsigned(bytes[valueOffset]) else unsignedInt16(bytes, valueOffset)
            if (value == 0) return false
            valueOffset += valueBytes
        }
        cursor += tableBytes
        present[table] = true
    }
    return cursor == end
}

private fun parseJpegHuffmanTables(
    bytes: ByteArray,
    start: Int,
    end: Int,
    present: Array<BooleanArray>,
): Boolean {
    var cursor = start
    if (cursor == end) return false
    while (cursor < end) {
        val definition = unsigned(bytes[cursor++])
        val tableClass = definition ushr 4
        val table = definition and 0x0f
        if (tableClass !in 0..1 || table !in present[tableClass].indices || end - cursor < 16) return false
        var symbolCount = 0
        var availableCodes = 1
        repeat(16) { index ->
            availableCodes = availableCodes * 2 - unsigned(bytes[cursor + index])
            if (availableCodes < 0) return false
            symbolCount += unsigned(bytes[cursor + index])
        }
        cursor += 16
        if (symbolCount !in 1..256 || symbolCount > end - cursor) return false
        repeat(symbolCount) { index ->
            val symbol = unsigned(bytes[cursor + index])
            if (tableClass == 0 && symbol > 11) return false
            if (tableClass == 1) {
                val run = symbol ushr 4
                val size = symbol and 0x0f
                if (size > 10 || size == 0 && run !in setOf(0, 15)) return false
            }
        }
        cursor += symbolCount
        present[tableClass][table] = true
    }
    return cursor == end
}

private fun parseJpegFrame(bytes: ByteArray, marker: Int, start: Int, end: Int): JpegFrame? {
    if (end - start < 6 || unsigned(bytes[start]) != 8) return null
    val height = unsignedInt16(bytes, start + 1).toLong()
    val width = unsignedInt16(bytes, start + 3).toLong()
    val componentCount = unsigned(bytes[start + 5])
    if (!validArtworkDimensions(width, height) || componentCount !in 1..4 || end - start != 6 + 3 * componentCount) {
        return null
    }
    val components = linkedMapOf<Int, JpegComponent>()
    repeat(componentCount) { index ->
        val offset = start + 6 + index * 3
        val id = unsigned(bytes[offset])
        val sampling = unsigned(bytes[offset + 1])
        val horizontalSampling = sampling ushr 4
        val verticalSampling = sampling and 0x0f
        val quantizationTable = unsigned(bytes[offset + 2])
        if (id in components || horizontalSampling !in 1..4 || verticalSampling !in 1..4 || quantizationTable !in 0..3) {
            return null
        }
        components[id] = JpegComponent(quantizationTable)
    }
    return JpegFrame(marker, width, height, components)
}

private fun parseJpegScanHeader(
    bytes: ByteArray,
    start: Int,
    end: Int,
    frame: JpegFrame,
    huffmanTables: Array<BooleanArray>,
): Set<Int>? {
    if (end - start < 4) return null
    val componentCount = unsigned(bytes[start])
    if (componentCount !in 1..frame.components.size || end - start != 4 + 2 * componentCount) return null
    val components = linkedSetOf<Int>()
    repeat(componentCount) { index ->
        val offset = start + 1 + index * 2
        val componentId = unsigned(bytes[offset])
        val selectors = unsigned(bytes[offset + 1])
        val dcTable = selectors ushr 4
        val acTable = selectors and 0x0f
        if (componentId !in frame.components || !components.add(componentId) || dcTable !in 0..3 || acTable !in 0..3) {
            return null
        }
        if (frame.marker == JPEG_SOF_BASELINE && (!huffmanTables[0][dcTable] || !huffmanTables[1][acTable])) {
            return null
        }
    }
    val parametersOffset = start + 1 + 2 * componentCount
    val spectralStart = unsigned(bytes[parametersOffset])
    val spectralEnd = unsigned(bytes[parametersOffset + 1])
    val approximation = unsigned(bytes[parametersOffset + 2])
    val approximationHigh = approximation ushr 4
    val approximationLow = approximation and 0x0f
    if (frame.marker == JPEG_SOF_BASELINE) {
        if (spectralStart != 0 || spectralEnd != 63 || approximation != 0) return null
    } else {
        if (spectralStart !in 0..63 || spectralEnd !in spectralStart..63 || approximationHigh > 13 ||
            approximationLow > 13 || spectralStart == 0 && spectralEnd != 0 ||
            spectralStart > 0 && componentCount != 1 ||
            approximationHigh != 0 && approximationHigh != approximationLow + 1
        ) return null
        repeat(componentCount) { index ->
            val selectors = unsigned(bytes[start + 2 + index * 2])
            val table = if (spectralStart == 0) selectors ushr 4 else selectors and 0x0f
            val tableClass = if (spectralStart == 0) 0 else 1
            if (!huffmanTables[tableClass][table]) return null
        }
    }
    return components
}

private fun scanJpegEntropy(
    bytes: ByteArray,
    start: Int,
    end: Int,
    restartInterval: Int,
    componentIds: Set<Int>,
): JpegScan? {
    var cursor = start
    var sawEntropyData = false
    var expectedRestart = 0
    while (cursor < end) {
        if (unsigned(bytes[cursor]) != 0xff) {
            sawEntropyData = true
            cursor += 1
            continue
        }
        val markerOffset = cursor
        while (cursor < end && unsigned(bytes[cursor]) == 0xff) cursor += 1
        if (cursor >= end) return null
        when (val marker = unsigned(bytes[cursor])) {
            0x00 -> {
                sawEntropyData = true
                cursor += 1
            }
            in JPEG_RESTART_MARKERS -> {
                if (restartInterval == 0 || marker != 0xd0 + expectedRestart || !sawEntropyData) return null
                expectedRestart = (expectedRestart + 1) and 7
                cursor += 1
            }
            else -> return if (sawEntropyData) JpegScan(componentIds, markerOffset) else null
        }
    }
    return null
}

private fun validPngColorFormat(bitDepth: Int, colorType: Int): Boolean = when (colorType) {
    0 -> bitDepth in setOf(1, 2, 4, 8, 16)
    2 -> bitDepth in setOf(8, 16)
    3 -> bitDepth in setOf(1, 2, 4, 8)
    4, 6 -> bitDepth in setOf(8, 16)
    else -> false
}

private fun validPngCrc(bytes: ByteArray, typeOffset: Int, dataEnd: Int, crcOffset: Int): Boolean {
    if (crcOffset + PNG_CRC_BYTES > bytes.size) return false
    val crc = CRC32().apply { update(bytes, typeOffset, dataEnd - typeOffset) }.value
    return crc == unsignedInt32(bytes, crcOffset)
}

private fun isPngChunkType(bytes: ByteArray, offset: Int): Boolean = (0 until 4).all { index ->
    unsigned(bytes[offset + index]) in 'A'.code..'Z'.code || unsigned(bytes[offset + index]) in 'a'.code..'z'.code
}

private fun isCriticalPngChunk(firstTypeByte: Byte): Boolean = unsigned(firstTypeByte) and 0x20 == 0

private fun validArtworkDimensions(width: Long, height: Long): Boolean {
    if (width !in 1..MAXIMUM_ARTWORK_DIMENSION || height !in 1..MAXIMUM_ARTWORK_DIMENSION) return false
    return width <= MAXIMUM_ARTWORK_PIXELS / height
}

private fun safeMultiply(left: Long, right: Long): Long? {
    if (left < 0 || right < 0 || left != 0L && right > Long.MAX_VALUE / left) return null
    return left * right
}

private fun matches(bytes: ByteArray, start: Int, end: Int, expected: IntArray): Boolean {
    if (start < 0 || expected.size > end - start) return false
    return expected.indices.all { index -> unsigned(bytes[start + index]) == expected[index] }
}

private fun ascii(bytes: ByteArray, start: Int, length: Int): String =
    bytes.copyOfRange(start, start + length).toString(Charsets.US_ASCII)

private fun unsigned(value: Byte): Int = value.toInt() and 0xff

private fun unsignedInt16(bytes: ByteArray, offset: Int): Int =
    (unsigned(bytes[offset]) shl 8) or unsigned(bytes[offset + 1])

private fun unsignedInt32(bytes: ByteArray, offset: Int): Long =
    (unsigned(bytes[offset]).toLong() shl 24) or
        (unsigned(bytes[offset + 1]).toLong() shl 16) or
        (unsigned(bytes[offset + 2]).toLong() shl 8) or
        unsigned(bytes[offset + 3]).toLong()

private val PNG_SIGNATURE = intArrayOf(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
private val ADAM7_PASSES = listOf(
    PngPass(0, 0, 8, 8),
    PngPass(4, 0, 8, 8),
    PngPass(0, 4, 4, 8),
    PngPass(2, 0, 4, 4),
    PngPass(0, 2, 2, 4),
    PngPass(1, 0, 2, 2),
    PngPass(0, 1, 1, 2),
)
private val JPEG_APP_MARKERS = 0xe0..0xef
private val JPEG_RESTART_MARKERS = 0xd0..0xd7
private const val JPEG_SOF_BASELINE = 0xc0
private const val JPEG_SOF_PROGRESSIVE = 0xc2
private const val JPEG_DHT = 0xc4
private const val JPEG_SOI = 0xd8
private const val JPEG_EOI = 0xd9
private const val JPEG_SOS = 0xda
private const val JPEG_DQT = 0xdb
private const val JPEG_DRI = 0xdd
private const val JPEG_COM = 0xfe
private const val JPEG_TEM = 0x01
private const val PNG_CHUNK_OVERHEAD = 12
private const val PNG_CRC_BYTES = 4
private const val PNG_MINIMUM_BYTES = 57
private const val PNG_INFLATE_BUFFER_BYTES = 8 * 1024
private const val MAXIMUM_PNG_CHUNKS = 4_096
private const val MAXIMUM_PNG_INFLATED_BYTES = 64L * 1024L * 1024L
private const val JPEG_MINIMUM_BYTES = 14
private const val MAXIMUM_JPEG_SEGMENTS = 1_024
private const val MAXIMUM_JPEG_DECODE_BYTES = 256 * 1024
private const val MAXIMUM_ARTWORK_DIMENSION = 8_192L
private const val MAXIMUM_ARTWORK_PIXELS = 32_000_000L
