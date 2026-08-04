package com.cleartune.data.webdav

import com.cleartune.core.model.MusicSource
import com.cleartune.core.model.SourceId
import com.cleartune.core.model.SourceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class WebDavSyncWorkerTest {
    private val source = MusicSource(SourceId("source-1"), "Remote", SourceType.WEBDAV, "https://music.example/dav/")

    @Test
    fun `process recreated worker resolves runner from application host`() {
        val runner = WebDavSyncRunner { WebDavWorkerOutcome.COMPLETED }
        val host = object : WebDavSyncWorkerHost { override val webDavSyncRunner = runner }

        assertSame(runner, WebDavSyncWorker.runnerFrom(host))
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

    @Test(expected = CancellationException::class)
    fun `durable runner rethrows cancellation`() = runTest {
        DurableWebDavSyncRunner(FakeSyncPort(source, null)) { _, _, _ ->
            throw CancellationException("stop")
        }.run(source.id)
    }
}

private class FakeSyncPort(
    private val source: MusicSource,
    private val checkpoint: WebDavSyncCheckpoint?,
) : WebDavSyncPort {
    val saved = mutableListOf<WebDavSyncCheckpoint>()
    var clearCount = 0
    override suspend fun loadSource(sourceId: SourceId): MusicSource? = source
    override suspend fun loadCheckpoint(sourceId: SourceId): WebDavSyncCheckpoint? = checkpoint
    override suspend fun saveCheckpoint(checkpoint: WebDavSyncCheckpoint) { saved += checkpoint }
    override suspend fun clearCheckpoint(sourceId: SourceId) { clearCount++ }
    override suspend fun remoteFingerprint(sourceId: SourceId, sourceKey: String): RemoteFingerprint? = null
    override suspend fun markUpdateAvailable(sourceId: SourceId, sourceKey: String) = Unit
}
