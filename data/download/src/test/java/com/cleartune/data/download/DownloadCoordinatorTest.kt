package com.cleartune.data.download

import com.cleartune.core.model.DownloadCommand
import com.cleartune.core.model.DownloadId
import com.cleartune.core.model.DownloadState
import com.cleartune.core.model.DownloadSummary
import com.cleartune.core.model.TrackId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadCoordinatorTest {
    @Test
    fun `duplicate enqueue schedules one durable record`() = runTest {
        val records = FakeRecords()
        val scheduler = FakeScheduler()
        val coordinator = DownloadCoordinator(records, scheduler)
        val track = TrackId("track-1")

        coordinator.dispatch(DownloadCommand.Enqueue(track))
        coordinator.dispatch(DownloadCommand.Enqueue(track))

        assertEquals(1, records.values.value.size)
        assertEquals(1, scheduler.enqueued.size)
    }

    @Test
    fun `pause resume cancel and retry persist state before scheduling`() = runTest {
        val id = DownloadId("download-1")
        val records = FakeRecords(DownloadSummary(id, TrackId("track-1"), DownloadState.RUNNING))
        val scheduler = FakeScheduler()
        val coordinator = DownloadCoordinator(records, scheduler)

        coordinator.dispatch(DownloadCommand.Pause(id))
        assertEquals(DownloadState.PAUSED, records.get(id)?.state)
        assertEquals(listOf(id), scheduler.stopped)

        coordinator.dispatch(DownloadCommand.Resume(id))
        assertEquals(DownloadState.QUEUED, records.get(id)?.state)
        assertEquals(listOf(id), scheduler.enqueued)

        records.replace(requireNotNull(records.get(id)).copy(state = DownloadState.FAILED, errorMessage = "network"))
        coordinator.dispatch(DownloadCommand.Retry(id))
        assertEquals(DownloadState.QUEUED, records.get(id)?.state)
        assertTrue(records.get(id)?.errorMessage == null)

        coordinator.dispatch(DownloadCommand.Cancel(id))
        assertEquals(DownloadState.CANCELED, records.get(id)?.state)
    }

    private class FakeRecords(vararg initial: DownloadSummary) : DownloadRecordStore {
        val values = MutableStateFlow(initial.toList())
        override fun observe(): Flow<List<DownloadSummary>> = values
        override suspend fun get(id: DownloadId) = values.value.firstOrNull { it.id == id }
        override suspend fun findByTrack(trackId: TrackId) = values.value.firstOrNull { it.trackId == trackId }
        override suspend fun insert(summary: DownloadSummary) { values.value += summary }
        override suspend fun replace(summary: DownloadSummary) {
            values.value = values.value.map { if (it.id == summary.id) summary else it }
        }
        override suspend fun remove(id: DownloadId) { values.value = values.value.filterNot { it.id == id } }
    }

    private class FakeScheduler : DownloadScheduler {
        val enqueued = mutableListOf<DownloadId>()
        val stopped = mutableListOf<DownloadId>()
        override suspend fun enqueue(id: DownloadId) { enqueued += id }
        override suspend fun stop(id: DownloadId) { stopped += id }
        override suspend fun deleteFiles(id: DownloadId) = Unit
    }
}
