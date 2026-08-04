package com.cleartune.data.local

import com.cleartune.core.database.LibrarySnapshotStore
import com.cleartune.core.database.model.LibraryIngestRecord
import com.cleartune.core.model.MutationResult
import com.cleartune.core.model.SourceId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LocalScanCoordinatorTest {
    @Test
    fun granted_scan_reads_media_and_applies_one_transactional_snapshot() = runTest {
        val store = RecordingSnapshotStore()
        val coordinator = LocalScanCoordinator(
            gateway = MediaStoreGateway {
                MediaStoreReadResult(listOf(snapshot("mediastore:1")), listOf("one malformed row"))
            },
            snapshotStore = store,
            clock = { 1234L },
        )

        val result = coordinator.scan(permissionGranted = true)

        assertEquals(1, store.records.size)
        assertEquals("本地音乐", store.sourceName)
        assertEquals(1234L, store.syncedAtEpochMs)
        assertEquals(1, result.mutation.inserted)
        assertEquals(listOf("one malformed row"), result.warnings)
    }

    @Test
    fun denied_scan_does_not_touch_media_or_the_last_good_database_snapshot() = runTest {
        var gatewayRead = false
        val store = RecordingSnapshotStore()
        val coordinator = LocalScanCoordinator(
            gateway = MediaStoreGateway {
                gatewayRead = true
                MediaStoreReadResult(emptyList())
            },
            snapshotStore = store,
        )

        val result = coordinator.scan(permissionGranted = false)

        assertFalse(gatewayRead)
        assertEquals(LocalScanOutcome.PERMISSION_REQUIRED, result.outcome)
        assertEquals(0, store.applyCount)
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

private class RecordingSnapshotStore : LibrarySnapshotStore {
    var applyCount = 0
    var records = emptyList<LibraryIngestRecord>()
    var sourceName = ""
    var syncedAtEpochMs = -1L

    override suspend fun applyLocalSnapshot(
        sourceId: SourceId,
        sourceName: String,
        records: List<LibraryIngestRecord>,
        syncedAtEpochMs: Long,
    ): MutationResult {
        applyCount++
        this.sourceName = sourceName
        this.records = records
        this.syncedAtEpochMs = syncedAtEpochMs
        return MutationResult(inserted = records.size)
    }
}
