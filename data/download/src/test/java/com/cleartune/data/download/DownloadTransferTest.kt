package com.cleartune.data.download

import java.nio.file.Files
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `mismatched range does not append corrupt bytes`() {
        server.enqueue(
            MockResponse.Builder().code(206)
                .addHeader("Content-Range", "bytes 0-4/11")
                .body("world")
                .build(),
        )
        val files = files().also { it.partialFile.writeText("hello ") }

        val result = DownloadTransfer(OkHttpClient()).execute(
            DownloadTransferRequest(server.url("/song.mp3"), files, expectedBytes = 11, etag = "v1"),
        )

        assertTrue(result is DownloadTransferResult.RetryableFailure)
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
        assertEquals("hello ", files.partialFile.readText())
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
