package com.cleartune.data.download

import com.cleartune.core.network.NetworkFailure
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

data class DownloadTransferRequest(
    val url: HttpUrl,
    val paths: DownloadPaths,
    val expectedBytes: Long? = null,
    val etag: String? = null,
)

sealed interface DownloadTransferResult {
    data class Completed(val bytes: Long) : DownloadTransferResult
    data class RetryableFailure(val code: String) : DownloadTransferResult
    data class PermanentFailure(val code: String) : DownloadTransferResult
}

class DownloadTransfer(client: OkHttpClient) {
    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun execute(
        request: DownloadTransferRequest,
        shouldContinue: () -> Boolean = { true },
        onProgress: (downloaded: Long, total: Long?) -> Unit = { _, _ -> },
    ): DownloadTransferResult {
        val partial = request.paths.partialFile
        val existingBytes = partial.takeIf { it.exists() }?.length() ?: 0L
        val offset = if (request.etag != null) existingBytes else 0L
        val httpRequest = Request.Builder().url(request.url).apply {
            if (offset > 0) {
                header("Range", "bytes=$offset-")
                request.etag?.let { header("If-Range", it) }
            }
        }.build()

        return try {
            client.newCall(httpRequest).execute().use { response ->
                if (response.code == 416) {
                    val remoteTotal = response.header("Content-Range")
                        ?.let(UNSATISFIED_CONTENT_RANGE::matchEntire)
                        ?.groupValues?.get(1)?.toLongOrNull()
                    val completeAndValidated = offset > 0 &&
                        (request.expectedBytes == null || request.expectedBytes == offset) &&
                        remoteTotal == offset &&
                        request.etag != null &&
                        response.header("ETag") == request.etag
                    if (completeAndValidated) return publish(request.paths, offset)
                    resetPartial(partial)
                    return DownloadTransferResult.RetryableFailure("range_not_satisfiable")
                }
                if (response.code !in listOf(200, 206)) {
                    val failure = NetworkFailure.fromHttpStatus(response.code)
                    return if (failure.retryable) {
                        DownloadTransferResult.RetryableFailure("http_${response.code}")
                    } else {
                        DownloadTransferResult.PermanentFailure("http_${response.code}")
                    }
                }

                val append = offset > 0 && response.code == 206
                val contentRange = if (response.code == 206) {
                    parseContentRange(response.header("Content-Range"))
                        ?.takeIf { range ->
                            range.start == offset &&
                                range.end >= range.start &&
                                range.total > range.end &&
                                (request.expectedBytes == null || range.total == request.expectedBytes)
                        }
                        ?: return DownloadTransferResult.RetryableFailure("invalid_content_range")
                } else {
                    null
                }
                if (append && request.etag != null && response.header("ETag") != request.etag) {
                    resetPartial(partial)
                    return DownloadTransferResult.RetryableFailure("etag_changed")
                }
                val body = response.body
                val declaredLength = body.contentLength().takeIf { it >= 0 }
                if (response.code == 200 &&
                    request.expectedBytes != null &&
                    declaredLength != null &&
                    declaredLength != request.expectedBytes
                ) {
                    resetPartial(partial)
                    return DownloadTransferResult.RetryableFailure("size_mismatch")
                }
                partial.parentFile?.mkdirs()
                val startingSize = if (append) offset else 0L
                val completedBytes = RandomAccessFile(partial, "rw").use { output ->
                    if (append) output.seek(offset) else output.setLength(0)
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var total = startingSize
                        var received = 0L
                        val maximumResponseBytes = contentRange?.let { it.end - it.start + 1 }
                        while (true) {
                            if (!shouldContinue()) {
                                output.fd.sync()
                                return DownloadTransferResult.RetryableFailure("interrupted")
                            }
                            val read = input.read(buffer)
                            if (read == -1) break
                            received += read
                            total += read
                            if ((maximumResponseBytes != null && received > maximumResponseBytes) ||
                                (contentRange != null && total > contentRange.total) ||
                                (request.expectedBytes != null && total > request.expectedBytes)
                            ) {
                                output.setLength(startingSize)
                                return DownloadTransferResult.RetryableFailure("size_mismatch")
                            }
                            output.write(buffer, 0, read)
                            onProgress(total, request.expectedBytes ?: contentRange?.total)
                        }
                        val complete = if (contentRange != null) {
                            received == maximumResponseBytes && total == contentRange.total
                        } else {
                            (declaredLength == null || received == declaredLength) &&
                                (request.expectedBytes == null || total == request.expectedBytes)
                        }
                        if (!complete) {
                            output.fd.sync()
                            return DownloadTransferResult.RetryableFailure("size_mismatch")
                        }
                        output.fd.sync()
                        total
                    }
                }
                publish(request.paths, completedBytes)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: IOException) {
            failure.findCancellation()?.let { throw it }
            DownloadTransferResult.RetryableFailure("io_error")
        } catch (_: SecurityException) {
            DownloadTransferResult.PermanentFailure("storage_denied")
        }
    }

    private fun parseContentRange(value: String?): ContentRange? {
        val match = value?.let(CONTENT_RANGE::matchEntire) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull() ?: return null
        return ContentRange(start, end, total)
    }

    private fun resetPartial(file: java.io.File) {
        if (!file.exists()) return
        RandomAccessFile(file, "rw").use { it.setLength(0) }
    }

    private fun publish(paths: DownloadPaths, bytes: Long): DownloadTransferResult {
        paths.finalFile.parentFile?.mkdirs()
        try {
            Files.move(
                paths.partialFile.toPath(),
                paths.finalFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                paths.partialFile.toPath(),
                paths.finalFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        return DownloadTransferResult.Completed(bytes)
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)
        val UNSATISFIED_CONTENT_RANGE = Regex("bytes \\*/(\\d+)", RegexOption.IGNORE_CASE)
    }
}

private data class ContentRange(val start: Long, val end: Long, val total: Long)

private fun Throwable.findCancellation(): CancellationException? =
    generateSequence(this) { it.cause }
        .filterIsInstance<CancellationException>()
        .firstOrNull()
