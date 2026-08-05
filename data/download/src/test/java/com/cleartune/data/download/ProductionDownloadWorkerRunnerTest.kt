package com.cleartune.data.download

import android.content.pm.ServiceInfo
import com.cleartune.core.model.DownloadId
import com.cleartune.core.model.DownloadState
import com.cleartune.core.model.DownloadSummary
import com.cleartune.core.model.TrackId
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.NetworkType
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
    fun `missing work reconciles its queued record to a terminal failure`() = runTest {
        val port = FakeDownloadPort(null)

        val outcome = ProductionDownloadWorkerRunner(port) {
            error("transfer must not start when work is missing")
        }.run(id)

        assertEquals(WorkerOutcome.FAILED, outcome)
        assertEquals("work_missing", port.failureCode)
    }

    @Test
    fun `process recreated worker resolves runner from application host`() {
        val runner = DownloadWorkerRunner { WorkerOutcome.COMPLETED }
        val host = object : DownloadWorkerHost { override val downloadWorkerRunner = runner }

        assertSame(runner, DownloadWorker.runnerFrom(host))
    }

    @Test
    fun `worker execution uses application host and maps every runner outcome`() = runTest {
        for ((runnerOutcome, expected) in listOf(
            WorkerOutcome.COMPLETED to DownloadWorkerExecutionResult.SUCCESS,
            WorkerOutcome.RETRY to DownloadWorkerExecutionResult.RETRY,
            WorkerOutcome.FAILED to DownloadWorkerExecutionResult.FAILURE,
        )) {
            var foregroundId: DownloadId? = null
            val host = object : DownloadWorkerHost {
                override val downloadWorkerRunner = DownloadWorkerRunner { runnerOutcome }
            }

            val actual = DownloadWorkerExecutor.execute(id.value, host) { foregroundId = it }

            assertEquals(expected, actual)
            assertEquals(id, foregroundId)
        }
    }

    @Test
    fun `worker execution rejects missing application host`() = runTest {
        assertEquals(
            DownloadWorkerExecutionResult.FAILURE,
            DownloadWorkerExecutor.execute(id.value, Any()) {},
        )
    }

    @Test(expected = CancellationException::class)
    fun `worker execution rethrows host runner cancellation`() = runTest {
        val host = object : DownloadWorkerHost {
            override val downloadWorkerRunner = DownloadWorkerRunner { throw CancellationException("stop") }
        }
        DownloadWorkerExecutor.execute(id.value, host) {}
    }

    @Test
    fun `download scheduler awaits accepted unique replacement and propagates rejection`() = runTest {
        val root = Files.createTempDirectory("download-scheduler-").toFile()
        val gateway = FakeDownloadWorkGateway()
        val scheduler = WorkManagerDownloadScheduler(root, DownloadFileLocator { null }, gateway)

        scheduler.enqueue(id)

        assertEquals(DownloadWorker.workName(id), gateway.enqueuedName)
        assertEquals(ExistingWorkPolicy.REPLACE, gateway.enqueuedPolicy)
        gateway.enqueueFailure = IllegalStateException("rejected")
        assertTrue(runCatching { scheduler.enqueue(id) }.isFailure)
    }

    @Test
    fun `download requests follow live wifi policy and replacements rebuild constraints`() = runTest {
        val root = Files.createTempDirectory("download-policy-").toFile()
        val gateway = FakeDownloadWorkGateway()
        var wifiOnly = true
        val scheduler = WorkManagerDownloadScheduler(
            root,
            DownloadFileLocator { null },
            gateway,
            DownloadNetworkPolicyProvider { wifiOnly },
        )

        scheduler.enqueue(id)
        assertEquals(NetworkType.UNMETERED, gateway.requests.single().workSpec.constraints.requiredNetworkType)

        wifiOnly = false
        scheduler.enqueue(id)
        assertEquals(NetworkType.CONNECTED, gateway.requests.last().workSpec.constraints.requiredNetworkType)
        assertEquals(listOf(ExistingWorkPolicy.REPLACE, ExistingWorkPolicy.REPLACE), gateway.policies)
    }

    @Test
    fun `foreground notification ids are stable and distinct per download`() {
        assertEquals(DownloadWorker.notificationId(id), DownloadWorker.notificationId(id))
        assertNotEquals(DownloadWorker.notificationId(id), DownloadWorker.notificationId(DownloadId("download-2")))
    }

    @Test
    fun `foreground download work declares Android 14 data sync service type`() {
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, DownloadWorker.foregroundServiceType)
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
        assertEquals(100L, port.progress.last().first)
        assertTrue(port.progress.map { it.first }.zipWithNext().all { (left, right) -> left < right })
        assertEquals("publish", port.events.last())
        assertEquals(fixture.final.absolutePath, port.publishedPath)
    }

    @Test
    fun `completed transfer without verified final file fails without publication`() = runTest {
        val fixture = fixture()
        val port = FakeDownloadPort(fixture.work, acceptGenerationWrites = false)
        val executor = DownloadTransferExecutor { _, _, _ -> DownloadTransferResult.Completed(100) }

        val outcome = ProductionDownloadWorkerRunner(port) { executor }.run(id)

        assertEquals(WorkerOutcome.FAILED, outcome)
        assertEquals(null, port.failureCode)
        assertEquals(listOf(41L), port.rejectedFailureGenerations)
        assertFalse(port.events.contains("publish"))
    }

    @Test
    fun `pause before queued progress keeps progress authoritative`() = runTest {
        val fixture = fixture()
        val port = FakeDownloadPort(fixture.work, acceptGenerationWrites = false)
        val executor = DownloadTransferExecutor { _, _, onProgress ->
            onProgress(40, 100)
            DownloadTransferResult.PermanentFailure("stopped")
        }

        ProductionDownloadWorkerRunner(port) { executor }.run(id)

        assertTrue(port.progress.isEmpty())
        assertEquals(listOf(41L), port.rejectedProgressGenerations)
        assertEquals(listOf(41L), port.rejectedFailureGenerations)
    }

    @Test
    fun `cancel before transfer failure cannot be overwritten by failed`() = runTest {
        val fixture = fixture()
        val port = FakeDownloadPort(fixture.work, acceptGenerationWrites = false)

        val outcome = ProductionDownloadWorkerRunner(port) {
            DownloadTransferExecutor { _, _, _ -> DownloadTransferResult.RetryableFailure("io_error") }
        }.run(id)

        assertEquals(WorkerOutcome.RETRY, outcome)
        assertEquals(null, port.failureCode)
        assertEquals(listOf(41L), port.rejectedFailureGenerations)
    }

    @Test
    fun `cancel before unexpected worker failure cannot be overwritten by failed`() = runTest {
        val fixture = fixture()
        val port = FakeDownloadPort(fixture.work, acceptGenerationWrites = false)

        val outcome = ProductionDownloadWorkerRunner(port) {
            DownloadTransferExecutor { _, _, _ -> error("boom") }
        }.run(id)

        assertEquals(WorkerOutcome.FAILED, outcome)
        assertEquals(null, port.failureCode)
        assertEquals(listOf(41L), port.rejectedFailureGenerations)
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

    @Test
    fun `fast progress producer has one pending persistence signal and publishes final progress first`() = runTest {
        val fixture = fixture()
        val firstPersistence = CompletableDeferred<Unit>()
        val releasePersistence = CompletableDeferred<Unit>()
        val port = FakeDownloadPort(
            fixture.work.copy(expectedBytes = 1_000),
            beforeProgress = { if (firstPersistence.complete(Unit)) releasePersistence.await() },
        )
        var maximumPending = 0
        val executor = DownloadTransferExecutor { _, _, onProgress ->
            repeat(1_000) { index -> onProgress((index + 1).toLong(), 1_000) }
            fixture.final.writeBytes(ByteArray(1_000))
            DownloadTransferResult.Completed(1_000)
        }
        val runner = ProductionDownloadWorkerRunner(
            port,
            progressQueueDepth = { pending -> maximumPending = maxOf(maximumPending, pending) },
        ) { executor }

        val result = async { runner.run(id) }
        firstPersistence.await()
        assertTrue(maximumPending <= 1)
        releasePersistence.complete(Unit)

        assertEquals(WorkerOutcome.COMPLETED, result.await())
        assertEquals(1_000L, port.progress.last().first)
        assertEquals("publish", port.events.last())
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

private class FakeDownloadWorkGateway : DownloadWorkManagerGateway {
    var enqueuedName: String? = null
    var enqueuedPolicy: ExistingWorkPolicy? = null
    var enqueueFailure: Exception? = null
    val requests = mutableListOf<OneTimeWorkRequest>()
    val policies = mutableListOf<ExistingWorkPolicy>()
    override suspend fun enqueueUnique(
        name: String,
        policy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    ) {
        enqueueFailure?.let { throw it }
        enqueuedName = name
        enqueuedPolicy = policy
        requests += request
        policies += policy
    }

    override suspend fun cancelUnique(name: String) = Unit
}

private data class Fixture(val work: DownloadWork, val final: File)

private class FakeDownloadPort(
    private val work: DownloadWork?,
    private val beforeProgress: suspend () -> Unit = {},
    private val acceptGenerationWrites: Boolean = true,
) : DownloadPersistencePort {
    val progress = mutableListOf<Pair<Long, Long?>>()
    val events = mutableListOf<String>()
    var publishedPath: String? = null
    var failureCode: String? = null
    val rejectedProgressGenerations = mutableListOf<Long>()
    val rejectedFailureGenerations = mutableListOf<Long>()

    override suspend fun loadWork(downloadId: DownloadId): DownloadWork? = work
    override suspend fun markRunning(downloadId: DownloadId) { events += "running" }
    override suspend fun beginWork(downloadId: DownloadId): Long = 41L
    override suspend fun persistProgress(
        downloadId: DownloadId,
        generation: Long,
        downloadedBytes: Long,
        totalBytes: Long?,
    ): Boolean {
        beforeProgress()
        if (!acceptGenerationWrites) {
            rejectedProgressGenerations += generation
            return false
        }
        progress += downloadedBytes to totalBytes
        events += "progress"
        return true
    }
    override suspend fun publishDownloadedLocation(downloadId: DownloadId, bytes: Long, finalPath: String) {
        publishedPath = finalPath
        events += "publish"
    }
    override suspend fun recordFailure(downloadId: DownloadId, generation: Long, code: String): Boolean {
        if (!acceptGenerationWrites) {
            rejectedFailureGenerations += generation
            return false
        }
        failureCode = code
        events += "failure"
        return true
    }
}
