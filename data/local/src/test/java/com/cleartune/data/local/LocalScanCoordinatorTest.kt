package com.cleartune.data.local

import com.cleartune.core.model.MutationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class LocalScanCoordinatorTest {
    @Test
    fun granted_scan_reads_media_and_applies_one_transactional_snapshot() = runTest {
        val port = RecordingSnapshotPort()
        val coordinator = LocalScanCoordinator(
            gateway = MediaStoreGateway {
                MediaStoreReadResult(listOf(snapshot("mediastore:1")), listOf("one malformed row"))
            },
            snapshotPort = port,
            sourceName = "Local music",
            clock = { 1234L },
        )

        val result = coordinator.scan(permissionGranted = true)

        assertEquals(1, port.request!!.records.size)
        assertEquals("Local music", port.request!!.sourceName)
        assertEquals(1234L, port.request!!.syncedAtEpochMs)
        assertEquals(1, port.request!!.warningCount)
        assertEquals(1, result.mutation.inserted)
        assertEquals(listOf("one malformed row"), result.warnings)
    }

    @Test
    fun denied_scan_does_not_touch_media_or_the_last_good_database_snapshot() = runTest {
        var gatewayRead = false
        val port = RecordingSnapshotPort()
        val coordinator = LocalScanCoordinator(
            gateway = MediaStoreGateway {
                gatewayRead = true
                MediaStoreReadResult(emptyList())
            },
            snapshotPort = port,
        )

        val result = coordinator.scan(permissionGranted = false)

        assertFalse(gatewayRead)
        assertEquals(LocalScanOutcome.PERMISSION_REQUIRED, result.outcome)
        assertEquals(0, port.applyCount)
        assertEquals(listOf(LocalScanPhase.PERMISSION_REQUIRED), port.reported.map { it.state.phase })
    }

    @Test
    fun incomplete_read_fails_without_applying_an_authoritative_empty_snapshot() = runTest {
        val port = RecordingSnapshotPort()
        val coordinator = LocalScanCoordinator(
            gateway = MediaStoreGateway {
                MediaStoreReadResult(emptyList(), listOf("MediaStore query returned no cursor"), isComplete = false)
            },
            snapshotPort = port,
        )

        val result = coordinator.scan(permissionGranted = true)

        assertEquals(LocalScanOutcome.FAILED, result.outcome)
        assertEquals(0, port.applyCount)
        assertEquals(listOf(LocalScanPhase.READING, LocalScanPhase.FAILED), port.reported.map { it.state.phase })
    }

    @Test
    fun observed_but_unmapped_rows_are_retained_during_a_complete_scan() = runTest {
        val port = RecordingSnapshotPort()
        val coordinator = LocalScanCoordinator(
            gateway = MediaStoreGateway {
                MediaStoreReadResult(
                    snapshots = listOf(snapshot("mediastore:1")),
                    warnings = listOf("one malformed row"),
                    observedSourceKeys = setOf("mediastore:1", "mediastore:2"),
                )
            },
            snapshotPort = port,
        )

        coordinator.scan(permissionGranted = true)

        assertEquals(setOf("mediastore:1", "mediastore:2"), port.request!!.retainedSourceKeys)
    }

    @Test
    fun io_failure_is_classified_as_transient() = runTest {
        val coordinator = LocalScanCoordinator(
            gateway = MediaStoreGateway { throw IOException("provider unavailable") },
            snapshotPort = RecordingSnapshotPort(),
        )

        val result = coordinator.scan(permissionGranted = true)

        assertEquals(LocalScanOutcome.TRANSIENT_FAILURE, result.outcome)
    }

    @Test
    fun unexpected_failure_is_classified_as_permanent() = runTest {
        val coordinator = LocalScanCoordinator(
            gateway = MediaStoreGateway { error("bad row contract") },
            snapshotPort = RecordingSnapshotPort(),
        )

        val result = coordinator.scan(permissionGranted = true)

        assertEquals(LocalScanOutcome.FAILED, result.outcome)
    }

    @Test
    fun cancellation_is_rethrown_after_progress_is_recorded() = runTest {
        val port = RecordingSnapshotPort()
        val coordinator = LocalScanCoordinator(
            gateway = MediaStoreGateway { throw CancellationException("stop") },
            snapshotPort = port,
        )

        try {
            coordinator.scan(permissionGranted = true)
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            // Expected: cancellation remains structured and observable to WorkManager.
        }

        assertEquals(listOf(LocalScanPhase.READING, LocalScanPhase.CANCELLED), port.reported.map { it.state.phase })
    }

    @Test
    fun progress_recording_failure_does_not_mask_scan_cancellation() = runTest {
        val cancellation = CancellationException("stop")
        val port = RecordingSnapshotPort(failOnPhase = LocalScanPhase.CANCELLED)
        val coordinator = LocalScanCoordinator(
            gateway = MediaStoreGateway { throw cancellation },
            snapshotPort = port,
        )

        try {
            coordinator.scan(permissionGranted = true)
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertEquals(cancellation, actual)
            assertEquals("progress write failed", actual.suppressed.single().message)
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

private class RecordingSnapshotPort(
    private val failOnPhase: LocalScanPhase? = null,
) : LocalSnapshotPort {
    var applyCount = 0
    var request: LocalSnapshotRequest? = null
    val reported = mutableListOf<LocalScanProgress>()

    override suspend fun applySnapshot(
        request: LocalSnapshotRequest,
        onProgress: suspend (processed: Int) -> Unit,
    ): MutationResult {
        applyCount++
        this.request = request
        request.records.indices.chunked(request.batchSize).forEach { batch ->
            onProgress(batch.last() + 1)
        }
        return MutationResult(inserted = request.records.size)
    }

    override suspend fun reportProgress(progress: LocalScanProgress) {
        if (progress.state.phase == failOnPhase) error("progress write failed")
        reported += progress
    }
}
