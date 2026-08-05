package com.cleartune.data.download

import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DownloadTransferTest {
    private lateinit var server: MockWebServer

    @Before fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After fun stopServer() = server.close()

    @Test
    fun `resumes a valid partial response and publishes atomically`() {
        server.enqueue(
            MockResponse.Builder().code(206)
                .addHeader("Content-Range", "bytes 6-10/11")
                .addHeader("ETag", "v1")
                .body("world")
                .build(),
        )
        val files = files().also { it.partialFile.writeText("hello ") }

        val result = DownloadTransfer(OkHttpClient()).execute(
            DownloadTransferRequest(server.url("/song.flac"), files, expectedBytes = 11, etag = "v1"),
        )

        assertTrue(result.toString(), result is DownloadTransferResult.Completed)
        assertEquals("bytes=6-", server.takeRequest().headers["Range"])
        assertEquals("hello world", files.finalFile.readText())
        assertFalse(files.partialFile.exists())
    }

    @Test
    fun `server ignoring range safely restarts from zero`() {
        server.enqueue(MockResponse.Builder().code(200).body("fresh").build())
        val files = files().also { it.partialFile.writeText("stale") }

        val result = DownloadTransfer(OkHttpClient()).execute(
            DownloadTransferRequest(server.url("/song.mp3"), files, expectedBytes = 5),
        )

        assertTrue(result.toString(), result is DownloadTransferResult.Completed)
        assertEquals("fresh", files.finalFile.readText())
    }

    @Test
    fun `200 validates declared content length before publication`() {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        object : ResponseBody() {
                            override fun contentType() = null
                            override fun contentLength() = 10L
                            override fun source() = Buffer().writeUtf8("short")
                        },
                    )
                    .build()
            }
            .build()
        val files = files()

        val result = DownloadTransfer(client).execute(
            DownloadTransferRequest(server.url("/song.mp3"), files),
        )

        assertEquals(DownloadTransferResult.RetryableFailure("size_mismatch"), result)
        assertEquals("short", files.partialFile.readText())
        assertFalse(files.finalFile.exists())
    }

    @Test
    fun `200 declared length conflicting with fixed metadata is permanent`() {
        server.enqueue(MockResponse.Builder().code(200).body("short").build())
        val files = files()

        val result = DownloadTransfer(OkHttpClient()).execute(
            DownloadTransferRequest(server.url("/song.mp3"), files, expectedBytes = 11),
        )

        assertEquals(DownloadTransferResult.PermanentFailure("size_mismatch"), result)
        assertEquals(0, files.partialFile.length())
        assertFalse(files.finalFile.exists())
    }

    @Test
    fun `mismatched range does not append corrupt bytes`() {
        server.enqueue(
            MockResponse.Builder().code(206)
                .addHeader("Content-Range", "bytes 0-4/11")
                .addHeader("ETag", "v1")
                .body("world")
                .build(),
        )
        val files = files().also { it.partialFile.writeText("hello ") }

        val result = DownloadTransfer(OkHttpClient()).execute(
            DownloadTransferRequest(server.url("/song.mp3"), files, expectedBytes = 11, etag = "v1"),
        )

        assertEquals(DownloadTransferResult.PermanentFailure("invalid_content_range"), result)
        assertEquals("hello ", files.partialFile.readText())
        assertFalse(files.finalFile.exists())
    }

    @Test
    fun `etag change rejects resumed content`() {
        server.enqueue(
            MockResponse.Builder().code(206)
                .addHeader("Content-Range", "bytes 6-10/11")
                .addHeader("ETag", "v2")
                .body("world")
                .build(),
        )
        val files = files().also { it.partialFile.writeText("hello ") }

        val result = DownloadTransfer(OkHttpClient()).execute(
            DownloadTransferRequest(server.url("/song.mp3"), files, expectedBytes = 11, etag = "v1"),
        )

        assertTrue(result is DownloadTransferResult.RetryableFailure)
        assertEquals(0, files.partialFile.length())
    }

    @Test
    fun `etag change resets stale partial before invalid range classification`() {
        server.enqueue(
            MockResponse.Builder().code(206)
                .addHeader("Content-Range", "bytes 0-4/11")
                .addHeader("ETag", "v2")
                .body("world")
                .build(),
        )
        val files = files().also { it.partialFile.writeText("hello ") }

        val result = DownloadTransfer(OkHttpClient()).execute(
            DownloadTransferRequest(server.url("/song.mp3"), files, expectedBytes = 11, etag = "v1"),
        )

        assertEquals(DownloadTransferResult.RetryableFailure("etag_changed"), result)
        assertEquals(0, files.partialFile.length())
        assertFalse(files.finalFile.exists())
    }

    @Test
    fun `unknown expected length uses content range total before publishing`() {
        server.enqueue(
            MockResponse.Builder().code(206)
                .addHeader("Content-Range", "bytes 6-10/11")
                .addHeader("ETag", "v1")
                .body("world")
                .build(),
        )
        val files = files().also { it.partialFile.writeText("hello ") }

        val result = DownloadTransfer(OkHttpClient()).execute(
            DownloadTransferRequest(server.url("/song.flac"), files, expectedBytes = null, etag = "v1"),
        )

        assertEquals(DownloadTransferResult.Completed(11), result)
        assertEquals("hello world", files.finalFile.readText())
    }

    @Test
    fun `truncated 206 retains bytes but is not published`() {
        server.enqueue(
            MockResponse.Builder().code(206)
                .addHeader("Content-Range", "bytes 6-10/11")
                .addHeader("ETag", "v1")
                .body("wor")
                .build(),
        )
        val files = files().also { it.partialFile.writeText("hello ") }

        val result = DownloadTransfer(OkHttpClient()).execute(
            DownloadTransferRequest(server.url("/song.flac"), files, expectedBytes = null, etag = "v1"),
        )

        assertEquals(DownloadTransferResult.RetryableFailure("size_mismatch"), result)
        assertEquals("hello wor", files.partialFile.readText())
        assertFalse(files.finalFile.exists())
    }

    @Test
    fun `206 with an unknown authoritative total is not published`() {
        server.enqueue(
            MockResponse.Builder().code(206)
                .addHeader("Content-Range", "bytes 0-4/*")
                .body("whole")
                .build(),
        )
        val files = files()

        val result = DownloadTransfer(OkHttpClient()).execute(
            DownloadTransferRequest(server.url("/song.flac"), files),
        )

        assertEquals(DownloadTransferResult.PermanentFailure("invalid_content_range"), result)
        assertFalse(files.finalFile.exists())
    }

    @Test
    fun `malformed content range is a permanent protocol mismatch`() {
        server.enqueue(
            MockResponse.Builder().code(206)
                .addHeader("Content-Range", "not-a-range")
                .body("whole")
                .build(),
        )

        val result = DownloadTransfer(OkHttpClient()).execute(
            DownloadTransferRequest(server.url("/song.flac"), files()),
        )

        assertEquals(DownloadTransferResult.PermanentFailure("invalid_content_range"), result)
    }

    @Test
    fun `client protocol statuses are permanent according to network failure classification`() {
        listOf(400, 405, 410, 423).forEach { status ->
            server.enqueue(MockResponse.Builder().code(status).build())
            val result = DownloadTransfer(OkHttpClient()).execute(
                DownloadTransferRequest(server.url("/song-$status.flac"), files()),
            )
            assertTrue("HTTP $status produced $result", result is DownloadTransferResult.PermanentFailure)
        }
    }

    @Test
    fun `server failures remain retryable according to network failure classification`() {
        server.enqueue(MockResponse.Builder().code(503).build())

        val result = DownloadTransfer(OkHttpClient()).execute(
            DownloadTransferRequest(server.url("/song.flac"), files()),
        )

        assertTrue(result is DownloadTransferResult.RetryableFailure)
    }

    @Test
    fun `valid newly received bytes survive a cooperative interruption`() {
        server.enqueue(
            MockResponse.Builder().code(206)
                .addHeader("Content-Range", "bytes 6-10/11")
                .addHeader("ETag", "v1")
                .body("world")
                .build(),
        )
        val files = files().also { it.partialFile.writeText("hello ") }
        var checks = 0

        val result = DownloadTransfer(OkHttpClient()).execute(
            DownloadTransferRequest(server.url("/song.flac"), files, expectedBytes = 11, etag = "v1"),
            shouldContinue = { checks++ == 0 },
        )

        assertEquals(DownloadTransferResult.RetryableFailure("interrupted"), result)
        assertEquals("hello world", files.partialFile.readText())
        assertFalse(files.finalFile.exists())
    }

    @Test
    fun `wrapped coroutine cancellation is rethrown`() {
        val cancellation = CancellationException("worker stopped")
        val client = OkHttpClient.Builder()
            .addInterceptor { throw IOException("call cancelled", cancellation) }
            .build()

        val thrown = assertThrows(CancellationException::class.java) {
            DownloadTransfer(client).execute(
                DownloadTransferRequest(server.url("/song.flac"), files()),
            )
        }

        assertEquals(cancellation, thrown)
    }

    @Test
    fun `partial without an etag restarts instead of joining file versions`() {
        server.enqueue(MockResponse.Builder().code(200).body("fresh").build())
        val files = files().also { it.partialFile.writeText("stale") }

        val result = DownloadTransfer(OkHttpClient()).execute(
            DownloadTransferRequest(server.url("/song.mp3"), files, expectedBytes = 5, etag = null),
        )

        assertTrue(result.toString(), result is DownloadTransferResult.Completed)
        assertEquals(null, server.takeRequest().headers["Range"])
        assertEquals("fresh", files.finalFile.readText())
    }

    @Test
    fun `416 publishes only when total and etag validate the complete partial`() {
        server.enqueue(
            MockResponse.Builder().code(416)
                .addHeader("Content-Range", "bytes */5")
                .addHeader("ETag", "v1")
                .build(),
        )
        val files = files().also { it.partialFile.writeText("whole") }

        val result = DownloadTransfer(OkHttpClient()).execute(
            DownloadTransferRequest(server.url("/song.mp3"), files, expectedBytes = 5, etag = "v1"),
        )

        assertTrue(result.toString(), result is DownloadTransferResult.Completed)
        assertEquals("whole", files.finalFile.readText())
    }

    @Test
    fun `unvalidated 416 discards stale partial and retries`() {
        server.enqueue(
            MockResponse.Builder().code(416)
                .addHeader("Content-Range", "bytes */5")
                .addHeader("ETag", "v2")
                .build(),
        )
        val files = files().also { it.partialFile.writeText("stale") }

        val result = DownloadTransfer(OkHttpClient()).execute(
            DownloadTransferRequest(server.url("/song.mp3"), files, expectedBytes = 5, etag = "v1"),
        )

        assertTrue(result is DownloadTransferResult.RetryableFailure)
        assertEquals(0, files.partialFile.length())
        assertFalse(files.finalFile.exists())
    }

    private fun files(): DownloadPaths {
        val root = Files.createTempDirectory("transfer").toFile()
        return DownloadPaths(root.resolve("song.part"), root.resolve("song"))
    }
}
