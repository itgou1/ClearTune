package com.cleartune.data.webdav

import java.util.Base64
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidJpegDecodeProbeTest {
    @Test
    fun decodesCompleteBaselineAndProgressiveJpegs() {
        assertTrue(AndroidJpegDecodeProbe.canDecode(BASELINE_JPEG, 0, BASELINE_JPEG.size, 1, 1))
        assertTrue(AndroidJpegDecodeProbe.canDecode(PROGRESSIVE_JPEG, 0, PROGRESSIVE_JPEG.size, 2, 2))
    }

    @Test
    fun rejectsTruncatedEntropyAndDimensionMismatch() {
        val scanStart = BASELINE_JPEG.jpegScanStart()
        val truncatedEntropy = BASELINE_JPEG.copyOfRange(0, scanStart) +
            byteArrayOf(0, 0xff.toByte(), 0xd9.toByte())

        assertFalse(AndroidJpegDecodeProbe.canDecode(truncatedEntropy, 0, truncatedEntropy.size, 1, 1))
        assertFalse(AndroidJpegDecodeProbe.canDecode(BASELINE_JPEG, 0, BASELINE_JPEG.size, 2, 1))
    }

    private fun ByteArray.jpegScanStart(): Int {
        val sos = indices.first { index ->
            index + 1 < size && (this[index].toInt() and 0xff) == 0xff &&
                (this[index + 1].toInt() and 0xff) == 0xda
        }
        val segmentLength = ((this[sos + 2].toInt() and 0xff) shl 8) or (this[sos + 3].toInt() and 0xff)
        return sos + 2 + segmentLength
    }

    private companion object {
        val BASELINE_JPEG: ByteArray = Base64.getDecoder().decode(
            "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoMDAsKCwsNDhIQDQ4RDgsLEBYQERMUFRUVDA8XGBYUGBIUFRT/2wBDAQMEBAUEBQkFBQkUDQsNFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBT/wAARCAABAAEDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD9U6KKKAP/2Q==",
        )
        val PROGRESSIVE_JPEG: ByteArray = Base64.getDecoder().decode(
            "/9j/4AAQSkZJRgABAgAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wgARCAACAAIDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAb/xAAUAQEAAAAAAAAAAAAAAAAAAAAD/9oADAMBAAIQAxAAAAGXCP8A/8QAFhABAQEAAAAAAAAAAAAAAAAAAgMB/9oACAEBAAEFAqJZX//EABYRAAMAAAAAAAAAAAAAAAAAAAABMf/aAAgBAwEBPwFQ/8QAFBEBAAAAAAAAAAAAAAAAAAAAAP/aAAgBAgEBPwF//8QAGBAAAgMAAAAAAAAAAAAAAAAAAAECIjH/2gAIAQEABj8CnZ6f/8QAGBABAAMBAAAAAAAAAAAAAAAAAQARIWH/2gAIAQEAAT8hMgAeX2f/2gAMAwEAAgADAAAAEAP/xAAVEQEBAAAAAAAAAAAAAAAAAAAAMf/aAAgBAwEBPxCT/8QAFhEAAwAAAAAAAAAAAAAAAAAAAAEx/9oACAECAQE/EFD/xAAXEAEAAwAAAAAAAAAAAAAAAAABABEh/9oACAEBAAE/ECDRgYBbJ//Z",
        )
    }
}
