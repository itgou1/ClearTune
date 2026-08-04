package com.cleartune.data.webdav

import android.content.pm.ServiceInfo
import com.cleartune.core.model.MusicSource
import com.cleartune.core.model.SourceId
import com.cleartune.core.model.SourceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class WebDavSyncWorkerTest {
    @Test
    fun `foreground WebDAV work declares Android 14 data sync service type`() {
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, WebDavSyncWorker.foregroundServiceType)
    }

    private val source = MusicSource(SourceId("source-1"), "Remote", SourceType.WEBDAV, "https://music.example/dav/")

    @Test
    fun `process recreated worker resolves runner from application host`() {
        val runner = WebDavSyncRunner { WebDavWorkerOutcome.COMPLETED }
        val host = object : WebDavSyncWorkerHost { override val webDavSyncRunner = runner }

        assertSame(runner, WebDavSyncWorker.runnerFrom(host))
    }

    @Test
    fun `worker execution uses application host and maps every runner outcome`() = runTest {
        for ((runnerOutcome, expected) in listOf(
            WebDavWorkerOutcome.COMPLETED to WebDavWorkerExecutionResult.SUCCESS,
            WebDavWorkerOutcome.RETRY to WebDavWorkerExecutionResult.RETRY,
            WebDavWorkerOutcome.FAILED to WebDavWorkerExecutionResult.FAILURE,
        )) {
            var foregroundId: SourceId? = null
            val host = object : WebDavSyncWorkerHost {
                override val webDavSyncRunner = WebDavSyncRunner { runnerOutcome }
            }

            val actual = WebDavWorkerExecutor.execute(source.id.value, host) { foregroundId = it }

            assertEquals(expected, actual)
            assertEquals(source.id, foregroundId)
        }
    }

    @Test
    fun `worker execution rejects missing application host`() = runTest {
        assertEquals(
            WebDavWorkerExecutionResult.FAILURE,
            WebDavWorkerExecutor.execute(source.id.value, Any()) {},
        )
    }

    @Test(expected = CancellationException::class)
    fun `worker execution rethrows host runner cancellation`() = runTest {
        val host = object : WebDavSyncWorkerHost {
            override val webDavSyncRunner = WebDavSyncRunner { throw CancellationException("stop") }
        }
        WebDavWorkerExecutor.execute(source.id.value, host) {}
    }

    @Test
    fun `sync scheduler replaces and cancels exact unique work name`() = runTest {
        val gateway = FakeWebDavWorkGateway()
        val scheduler = WorkManagerWebDavSyncScheduler(gateway)

        scheduler.enqueue(source.id)
        scheduler.cancel(source.id)

        assertEquals(WebDavSyncWorker.workName(source.id), gateway.enqueuedName)
        assertEquals(ExistingWorkPolicy.REPLACE, gateway.enqueuedPolicy)
        assertEquals(WebDavSyncWorker.workName(source.id), gateway.canceledName)
    }

    @Test
    fun `unique work identity and notification id are stable per source`() {
        val id = SourceId("source-1")

        assertEquals("webdav-sync-source-1", WebDavSyncWorker.workName(id))
        assertEquals(WebDavSyncWorker.notificationId(id), WebDavSyncWorker.notificationId(id))
    }

    @Test
    fun `durable runner resumes checkpoint and clears it only after success`() = runTest {
        val checkpoint = WebDavSyncCheckpoint(source.id, listOf("https://music.example/dav/"))
        val port = FakeSyncPort(source, checkpoint)
        val runner = DurableWebDavSyncRunner(port) { _, loaded, save ->
            assertSame(checkpoint, loaded)
            save(requireNotNull(loaded).copy(pendingDirectories = emptyList()))
            WebDavSyncReport(0, 1, emptyList())
        }

        assertEquals(WebDavWorkerOutcome.COMPLETED, runner.run(source.id))
        assertEquals(1, port.saved.size)
        assertEquals(1, port.clearCount)
    }

    @Test
    fun `only classified transient sync failures retry`() = runTest {
        val transient = FakeSyncPort(source, null)
        val permanent = FakeSyncPort(source, null)

        val retry = DurableWebDavSyncRunner(transient) { _, _, _ ->
            throw WebDavSyncException("server", retryable = true)
        }
        val fail = DurableWebDavSyncRunner(permanent) { _, _, _ ->
            throw WebDavSyncException("auth", retryable = false)
        }

        assertEquals(WebDavWorkerOutcome.RETRY, retry.run(source.id))
        assertEquals(WebDavWorkerOutcome.FAILED, fail.run(source.id))
    }

    @Test
    fun `explicit run after permanent directory failure restarts from root`() = runTest {
        val port = FakeSyncPort(source, null)
        var attempts = 0
        val runner = DurableWebDavSyncRunner(port) { _, checkpoint, save ->
            attempts++
            if (attempts == 1) {
                assertNull(checkpoint)
                save(
                    WebDavSyncCheckpoint(
                        source.id,
                        pendingDirectories = emptyList(),
                        failures = listOf(SyncFailure("private", retryable = false)),
                    ),
                )
                WebDavSyncReport(0, 1, listOf(SyncFailure("private", retryable = false)))
            } else {
                assertNull("terminal checkpoint must not strand an explicit retry", checkpoint)
                WebDavSyncReport(1, 1, emptyList())
            }
        }

        assertEquals(WebDavWorkerOutcome.FAILED, runner.run(source.id))
        assertEquals(WebDavWorkerOutcome.COMPLETED, runner.run(source.id))
        assertEquals(2, attempts)
    }

    @Test(expected = CancellationException::class)
    fun `durable runner rethrows cancellation`() = runTest {
        DurableWebDavSyncRunner(FakeSyncPort(source, null)) { _, _, _ ->
            throw CancellationException("stop")
        }.run(source.id)
    }
}

private class FakeWebDavWorkGateway : WebDavWorkManagerGateway {
    var enqueuedName: String? = null
    var enqueuedPolicy: ExistingWorkPolicy? = null
    var canceledName: String? = null
    override suspend fun enqueueUnique(
        name: String,
        policy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    ) {
        enqueuedName = name
        enqueuedPolicy = policy
    }

    override suspend fun cancelUnique(name: String) {
        canceledName = name
    }
}

private class FakeSyncPort(
    private val source: MusicSource,
    checkpoint: WebDavSyncCheckpoint?,
) : WebDavSyncPort {
    private var checkpoint = checkpoint
    val saved = mutableListOf<WebDavSyncCheckpoint>()
    var clearCount = 0
    override suspend fun loadSource(sourceId: SourceId): MusicSource? = source
    override suspend fun loadCheckpoint(sourceId: SourceId): WebDavSyncCheckpoint? = checkpoint
    override suspend fun saveCheckpoint(checkpoint: WebDavSyncCheckpoint) {
        saved += checkpoint
        this.checkpoint = checkpoint
    }
    override suspend fun clearCheckpoint(sourceId: SourceId) {
        clearCount++
        checkpoint = null
    }
    override suspend fun remoteFingerprint(sourceId: SourceId, sourceKey: String): RemoteFingerprint? = null
    override suspend fun markUpdateAvailable(sourceId: SourceId, sourceKey: String) = Unit
}
