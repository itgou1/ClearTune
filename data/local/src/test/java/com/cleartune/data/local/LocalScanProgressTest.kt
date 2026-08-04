package com.cleartune.data.local

import com.cleartune.core.model.MutationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LocalScanProgressTest {
    @Test
    fun snapshot_progress_is_monotonic_and_uses_bounded_batches() = runTest {
        val port = ProgressRecordingPort()
        val snapshots = (1..205).map { index -> snapshot("mediastore:$index") }
        val coordinator = LocalScanCoordinator(
            gateway = MediaStoreGateway { MediaStoreReadResult(snapshots) },
            snapshotPort = port,
            clock = { 100 },
        )

        coordinator.scan(permissionGranted = true)

        val applying = port.progress.map(LocalScanProgress::state)
            .filter { it.phase == LocalScanPhase.APPLYING }
        assertEquals(100, port.request!!.batchSize)
        assertEquals(listOf(0, 100, 200, 205), applying.map(LocalScanState::processed))
        assertTrue(applying.zipWithNext().all { (before, after) -> after.processed >= before.processed })
        assertTrue(applying.all { it.total == 205 })
    }

    @Test
    fun missing_worker_provisioning_is_a_permanent_failure() = runTest {
        assertEquals(LocalWorkerDecision.FAILURE, LocalScanWorkExecutor(null).execute())
    }

    @Test
    fun permanent_scan_failure_does_not_retry() = runTest {
        val runner = LocalScanWorkerRunner { LocalScanResult(LocalScanOutcome.FAILED) }

        assertEquals(LocalWorkerDecision.FAILURE, LocalScanWorkExecutor(runner).execute())
    }

    @Test
    fun transient_scan_failure_retries() = runTest {
        val runner = LocalScanWorkerRunner { LocalScanResult(LocalScanOutcome.TRANSIENT_FAILURE) }

        assertEquals(LocalWorkerDecision.RETRY, LocalScanWorkExecutor(runner).execute())
    }

    @Test
    fun worker_cancellation_is_rethrown() = runTest {
        val runner = LocalScanWorkerRunner { throw CancellationException("stop") }

        try {
            LocalScanWorkExecutor(runner).execute()
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            // Expected.
        }
    }

    private fun snapshot(sourceKey: String) = LocalAudioSnapshot(
        sourceKey = sourceKey,
        contentUri = "content://$sourceKey",
        displayName = "Track.mp3",
        relativeFolder = "Music",
        title = "Track",
        album = null,
        artistNames = emptyList(),
        durationMs = 1_000,
        sizeBytes = 10,
        modifiedEpochSeconds = 1,
    )
}

private class ProgressRecordingPort : LocalSnapshotPort {
    var request: LocalSnapshotRequest? = null
    val progress = mutableListOf<LocalScanProgress>()

    override suspend fun applySnapshot(
        request: LocalSnapshotRequest,
        onProgress: suspend (processed: Int) -> Unit,
    ): MutationResult {
        this.request = request
        request.records.indices.chunked(request.batchSize).forEach { batch ->
            onProgress(batch.last() + 1)
        }
        return MutationResult(inserted = request.records.size)
    }

    override suspend fun reportProgress(progress: LocalScanProgress) {
        this.progress += progress
    }
}
