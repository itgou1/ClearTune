package com.cleartune.data.local

import com.cleartune.core.model.MutationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.FileNotFoundException

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
    fun failed_scan_preserves_the_complete_monotonic_progress_sequence() = runTest {
        val port = ProgressRecordingPort(
            stopAfterProcessed = 100,
            stopFailure = FileNotFoundException("media disappeared"),
        )
        val coordinator = LocalScanCoordinator(
            gateway = MediaStoreGateway { MediaStoreReadResult((1..205).map { snapshot("mediastore:$it") }) },
            snapshotPort = port,
            clock = { 100 },
        )

        coordinator.scan(permissionGranted = true)

        assertEquals(
            listOf(
                ProgressEmission(LocalScanPhase.READING, 0, 0),
                ProgressEmission(LocalScanPhase.APPLYING, 0, 205),
                ProgressEmission(LocalScanPhase.APPLYING, 100, 205),
                ProgressEmission(LocalScanPhase.FAILED, 100, 205),
            ),
            port.progress.map { ProgressEmission(it.state.phase, it.state.processed, it.state.total) },
        )
    }

    @Test
    fun cancelled_scan_preserves_the_complete_monotonic_progress_sequence() = runTest {
        val port = ProgressRecordingPort(
            stopAfterProcessed = 100,
            stopFailure = CancellationException("stop"),
        )
        val coordinator = LocalScanCoordinator(
            gateway = MediaStoreGateway { MediaStoreReadResult((1..205).map { snapshot("mediastore:$it") }) },
            snapshotPort = port,
            clock = { 100 },
        )

        try {
            coordinator.scan(permissionGranted = true)
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            // Expected.
        }

        assertEquals(
            listOf(
                ProgressEmission(LocalScanPhase.READING, 0, 0),
                ProgressEmission(LocalScanPhase.APPLYING, 0, 205),
                ProgressEmission(LocalScanPhase.APPLYING, 100, 205),
                ProgressEmission(LocalScanPhase.CANCELLED, 100, 205),
            ),
            port.progress.map { ProgressEmission(it.state.phase, it.state.processed, it.state.total) },
        )
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

private data class ProgressEmission(
    val phase: LocalScanPhase,
    val processed: Int,
    val total: Int,
)

private class ProgressRecordingPort(
    private val stopAfterProcessed: Int? = null,
    private val stopFailure: Throwable? = null,
) : LocalSnapshotPort {
    var request: LocalSnapshotRequest? = null
    val progress = mutableListOf<LocalScanProgress>()

    override suspend fun applySnapshot(
        request: LocalSnapshotRequest,
        onProgress: suspend (processed: Int) -> Unit,
    ): MutationResult {
        this.request = request
        request.records.indices.chunked(request.batchSize).forEach { batch ->
            val processed = batch.last() + 1
            onProgress(processed)
            if (stopAfterProcessed != null && processed >= stopAfterProcessed) throw requireNotNull(stopFailure)
        }
        return MutationResult(inserted = request.records.size)
    }

    override suspend fun reportProgress(progress: LocalScanProgress) {
        this.progress += progress
    }
}
