package com.cleartune.data.download

import com.cleartune.core.model.DownloadId
import com.cleartune.core.model.DownloadState
import com.cleartune.core.model.DownloadSummary
import com.cleartune.core.model.TrackId
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionDownloadWorkerRunnerTest {
    private val id = DownloadId("download-1")

    @Test
    fun `process recreated worker resolves runner from application host`() {
        val runner = DownloadWorkerRunner { WorkerOutcome.COMPLETED }
        val host = object : DownloadWorkerHost { override val downloadWorkerRunner = runner }

        assertSame(runner, DownloadWorker.runnerFrom(host))
    }

    @Test
    fun `foreground notification ids are stable and distinct per download`() {
        assertEquals(DownloadWorker.notificationId(id), DownloadWorker.notificationId(id))
        assertNotEquals(DownloadWorker.notificationId(id), DownloadWorker.notificationId(DownloadId("download-2")))
    }

    @Test
    fun `persists monotonic progress and publishes verified final location last`() = runTest {
        val fixture = fixture()
        val port = FakeDownloadPort(fixture.work)
        val executor = DownloadTransferExecutor { _, _, onProgress ->
            onProgress(20, 100)
            onProgress(10, 100)
            onProgress(80, 100)
            fixture.final.writeBytes(ByteArray(100))
            DownloadTransferResult.Completed(100)
        }

        val outcome = ProductionDownloadWorkerRunner(port) { executor }.run(id)

        assertEquals(WorkerOutcome.COMPLETED, outcome)
        assertEquals(listOf(20L, 80L), port.progress.map { it.first })
        assertEquals("publish", port.events.last())
        assertEquals(fixture.final.absolutePath, port.publishedPath)
    }

    @Test
    fun `completed transfer without verified final file fails without publication`() = runTest {
        val fixture = fixture()
        val port = FakeDownloadPort(fixture.work)
        val executor = DownloadTransferExecutor { _, _, _ -> DownloadTransferResult.Completed(100) }

        val outcome = ProductionDownloadWorkerRunner(port) { executor }.run(id)

        assertEquals(WorkerOutcome.FAILED, outcome)
        assertEquals("final_file_missing", port.failureCode)
        assertFalse(port.events.contains("publish"))
    }

    @Test
    fun `only retryable transfer failures request worker retry`() = runTest {
        val retryFixture = fixture()
        val failFixture = fixture()
        val retryPort = FakeDownloadPort(retryFixture.work)
        val failPort = FakeDownloadPort(failFixture.work)

        val retry = ProductionDownloadWorkerRunner(retryPort) {
            DownloadTransferExecutor { _, _, _ -> DownloadTransferResult.RetryableFailure("io_error") }
        }.run(id)
        val fail = ProductionDownloadWorkerRunner(failPort) {
            DownloadTransferExecutor { _, _, _ -> DownloadTransferResult.PermanentFailure("storage_denied") }
        }.run(id)

        assertEquals(WorkerOutcome.RETRY, retry)
        assertEquals(WorkerOutcome.FAILED, fail)
        assertEquals("io_error", retryPort.failureCode)
        assertEquals("storage_denied", failPort.failureCode)
    }

    @Test(expected = CancellationException::class)
    fun `runner rethrows cancellation and clears credential buffer`() = runTest {
        val fixture = fixture()
        val password = charArrayOf('s', 'e', 'c', 'r', 'e', 't')
        val port = FakeDownloadPort(fixture.work.copy(credentials = DownloadCredentials("alice", password)))
        try {
            ProductionDownloadWorkerRunner(port) {
                DownloadTransferExecutor { _, _, _ -> throw CancellationException("stop") }
            }.run(id)
        } finally {
            assertTrue(password.all { it == '\u0000' })
        }
    }

    private fun fixture(): Fixture {
        val root = Files.createTempDirectory("download-runner-").toFile()
        val partial = File(root, "track.part")
        val final = File(root, "track.mp3")
        val work = DownloadWork(
            summary = DownloadSummary(id, TrackId("track-1"), DownloadState.QUEUED),
            url = "https://music.example/track.mp3".toHttpUrl(),
            paths = DownloadPaths(partial, final),
            expectedBytes = 100,
            etag = "v1",
        )
        return Fixture(work, final)
    }
}

private data class Fixture(val work: DownloadWork, val final: File)

private class FakeDownloadPort(private val work: DownloadWork?) : DownloadPersistencePort {
    val progress = mutableListOf<Pair<Long, Long?>>()
    val events = mutableListOf<String>()
    var publishedPath: String? = null
    var failureCode: String? = null

    override suspend fun loadWork(downloadId: DownloadId): DownloadWork? = work
    override suspend fun markRunning(downloadId: DownloadId) { events += "running" }
    override suspend fun persistProgress(downloadId: DownloadId, downloadedBytes: Long, totalBytes: Long?) {
        progress += downloadedBytes to totalBytes
        events += "progress"
    }
    override suspend fun publishDownloadedLocation(downloadId: DownloadId, bytes: Long, finalPath: String) {
        publishedPath = finalPath
        events += "publish"
    }
    override suspend fun recordFailure(downloadId: DownloadId, code: String) {
        failureCode = code
        events += "failure"
    }
}
