package com.cleartune.data.webdav

import android.annotation.TargetApi
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import java.nio.ByteBuffer
import kotlin.math.max

internal object AndroidJpegDecodeProbe : JpegDecodeProbe {
    override fun canDecode(
        bytes: ByteArray,
        start: Int,
        end: Int,
        expectedWidth: Int,
        expectedHeight: Int,
    ): Boolean {
        if (start < 0 || end <= start || end > bytes.size || expectedWidth <= 0 || expectedHeight <= 0) {
            return false
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                decodeWithImageDecoder(bytes, start, end, expectedWidth, expectedHeight)
            } else {
                decodeWithBitmapFactory(bytes, start, end, expectedWidth, expectedHeight)
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun decodeWithBitmapFactory(
        bytes: ByteArray,
        start: Int,
        end: Int,
        expectedWidth: Int,
        expectedHeight: Int,
    ): Boolean {
        val length = end - start
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, start, length, bounds)
        if (bounds.outWidth != expectedWidth || bounds.outHeight != expectedHeight ||
            bounds.outMimeType != "image/jpeg"
        ) return false

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(expectedWidth, expectedHeight)
            inPreferredConfig = Bitmap.Config.RGB_565
            inScaled = false
            inMutable = false
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, start, length, options) ?: return false
        return validateAndRecycle(bitmap)
    }

    @TargetApi(Build.VERSION_CODES.P)
    private fun decodeWithImageDecoder(
        bytes: ByteArray,
        start: Int,
        end: Int,
        expectedWidth: Int,
        expectedHeight: Int,
    ): Boolean {
        val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes, start, end - start).slice())
        val targetDivisor = max(1, max(ceilDiv(expectedWidth, MAXIMUM_DECODE_DIMENSION), ceilDiv(expectedHeight, MAXIMUM_DECODE_DIMENSION)))
        val targetWidth = max(1, expectedWidth / targetDivisor)
        val targetHeight = max(1, expectedHeight / targetDivisor)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            if (info.size.width != expectedWidth || info.size.height != expectedHeight) {
                throw IllegalArgumentException("JPEG dimensions changed between validation and decode")
            }
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
            decoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM)
            decoder.setTargetSize(targetWidth, targetHeight)
            decoder.setOnPartialImageListener { false }
        }
        return validateAndRecycle(bitmap)
    }

    private fun validateAndRecycle(bitmap: Bitmap): Boolean = try {
        bitmap.width in 1..MAXIMUM_DECODE_DIMENSION && bitmap.height in 1..MAXIMUM_DECODE_DIMENSION
    } finally {
        bitmap.recycle()
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (ceilDiv(width, sample) > MAXIMUM_DECODE_DIMENSION ||
            ceilDiv(height, sample) > MAXIMUM_DECODE_DIMENSION
        ) {
            sample *= 2
        }
        return sample
    }

    private fun ceilDiv(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor

    private const val MAXIMUM_DECODE_DIMENSION = 64
}
