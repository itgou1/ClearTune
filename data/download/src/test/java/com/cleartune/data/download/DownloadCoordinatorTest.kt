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

    @Test
    fun `enqueue revives an existing canceled record`() = runTest {
        val id = DownloadId("download-1")
        val track = TrackId("track-1")
        val records = FakeRecords(DownloadSummary(id, track, DownloadState.CANCELED))
        val scheduler = FakeScheduler()

        DownloadCoordinator(records, scheduler).dispatch(DownloadCommand.Enqueue(track))

        assertEquals(DownloadState.QUEUED, records.get(id)?.state)
        assertEquals(listOf(id), scheduler.enqueued)
    }

    @Test
    fun `retry explicitly revives a canceled record`() = runTest {
        val id = DownloadId("download-1")
        val records = FakeRecords(DownloadSummary(id, TrackId("track-1"), DownloadState.CANCELED))
        val scheduler = FakeScheduler()

        DownloadCoordinator(records, scheduler).dispatch(DownloadCommand.Retry(id))

        assertEquals(DownloadState.QUEUED, records.get(id)?.state)
        assertEquals(listOf(id), scheduler.enqueued)
    }

    @Test
    fun `scheduler enqueue failure reconciles persisted record to failed`() = runTest {
        val records = FakeRecords()
        val scheduler = FakeScheduler().apply { enqueueFailure = IllegalStateException("private detail") }

        DownloadCoordinator(records, scheduler).dispatch(DownloadCommand.Enqueue(TrackId("track-1")))

        assertEquals(DownloadState.FAILED, records.values.value.single().state)
        assertEquals("Unable to schedule download", records.values.value.single().errorMessage)
    }

    @Test
    fun `resume scheduler failure reconciles queued record to failed`() = runTest {
        val id = DownloadId("download-1")
        val records = FakeRecords(DownloadSummary(id, TrackId("track-1"), DownloadState.PAUSED))
        val scheduler = FakeScheduler().apply { enqueueFailure = IllegalStateException("private detail") }

        DownloadCoordinator(records, scheduler).dispatch(DownloadCommand.Resume(id))

        assertEquals(DownloadState.FAILED, records.get(id)?.state)
        assertEquals("Unable to schedule download", records.get(id)?.errorMessage)
    }

    @Test
    fun `cancel stop failure remains canceled and tracked for reconciliation`() = runTest {
        val id = DownloadId("download-1")
        val records = FakeRecords(DownloadSummary(id, TrackId("track-1"), DownloadState.RUNNING))
        val scheduler = FakeScheduler().apply { stopFailure = IllegalStateException("scheduler down") }

        DownloadCoordinator(records, scheduler).dispatch(DownloadCommand.Cancel(id))

        assertEquals(DownloadState.CANCELED, records.get(id)?.state)
        assertEquals("Unable to stop download; cleanup pending", records.get(id)?.errorMessage)
    }

    @Test
    fun `cancel file cleanup failure never rolls record back to running`() = runTest {
        val id = DownloadId("download-1")
        val records = FakeRecords(DownloadSummary(id, TrackId("track-1"), DownloadState.RUNNING))
        val scheduler = FakeScheduler().apply { deleteFailure = java.io.IOException("locked") }

        DownloadCoordinator(records, scheduler).dispatch(DownloadCommand.Cancel(id))

        assertEquals(DownloadState.CANCELED, records.get(id)?.state)
        assertEquals("Download canceled; file cleanup pending", records.get(id)?.errorMessage)
        assertEquals(listOf(id), scheduler.stopped)
    }

    @Test
    fun `delete cleanup failure keeps a canceled record until cleanup can be retried`() = runTest {
        val id = DownloadId("download-1")
        val records = FakeRecords(DownloadSummary(id, TrackId("track-1"), DownloadState.COMPLETED, finalPath = "track.mp3"))
        val scheduler = FakeScheduler().apply { deleteFailure = java.io.IOException("locked") }

        DownloadCoordinator(records, scheduler).dispatch(DownloadCommand.Delete(id))

        assertEquals(DownloadState.CANCELED, records.get(id)?.state)
        assertEquals("Delete incomplete; file cleanup pending", records.get(id)?.errorMessage)
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
        var enqueueFailure: Exception? = null
        var stopFailure: Exception? = null
        var deleteFailure: Exception? = null
        override suspend fun enqueue(id: DownloadId) {
            enqueueFailure?.let { throw it }
            enqueued += id
        }
        override suspend fun stop(id: DownloadId) {
            stopFailure?.let { throw it }
            stopped += id
        }
        override suspend fun deleteFiles(id: DownloadId) {
            deleteFailure?.let { throw it }
        }
    }
}
