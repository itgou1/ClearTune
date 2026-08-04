package com.cleartune.data.download

import com.cleartune.core.contracts.DownloadRepository
import com.cleartune.core.model.DownloadCommand
import com.cleartune.core.model.DownloadId
import com.cleartune.core.model.DownloadState
import com.cleartune.core.model.DownloadSummary
import com.cleartune.core.model.TrackId
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface DownloadRecordStore {
    fun observe(): Flow<List<DownloadSummary>>
    suspend fun get(id: DownloadId): DownloadSummary?
    suspend fun findByTrack(trackId: TrackId): DownloadSummary?
    suspend fun insert(summary: DownloadSummary)
    suspend fun replace(summary: DownloadSummary)
    suspend fun remove(id: DownloadId)
}

interface DownloadScheduler {
    suspend fun enqueue(id: DownloadId)
    suspend fun stop(id: DownloadId)
    suspend fun deleteFiles(id: DownloadId)
}

class DownloadCoordinator(
    private val records: DownloadRecordStore,
    private val scheduler: DownloadScheduler,
) : DownloadRepository {
    private val commandMutex = Mutex()

    override fun observeDownloads(): Flow<List<DownloadSummary>> = records.observe()

    override suspend fun dispatch(command: DownloadCommand) = commandMutex.withLock {
        when (command) {
            is DownloadCommand.Enqueue -> enqueue(command.trackId)
            is DownloadCommand.Pause -> transition(command.downloadId, DownloadState.PAUSED) {
                scheduler.stop(command.downloadId)
            }
            is DownloadCommand.Resume -> transition(command.downloadId, DownloadState.QUEUED) {
                scheduler.enqueue(command.downloadId)
            }
            is DownloadCommand.Retry -> transition(command.downloadId, DownloadState.QUEUED) {
                scheduler.enqueue(command.downloadId)
            }
            is DownloadCommand.Cancel -> transition(command.downloadId, DownloadState.CANCELED) {
                scheduler.stop(command.downloadId)
                scheduler.deleteFiles(command.downloadId)
            }
            is DownloadCommand.Delete -> {
                records.get(command.downloadId) ?: return@withLock
                scheduler.stop(command.downloadId)
                scheduler.deleteFiles(command.downloadId)
                records.remove(command.downloadId)
            }
        }
    }

    private suspend fun enqueue(trackId: TrackId) {
        if (records.findByTrack(trackId) != null) return
        val id = DownloadId(
            UUID.nameUUIDFromBytes("cleartune-download:${trackId.value}".toByteArray(StandardCharsets.UTF_8))
                .toString(),
        )
        records.insert(DownloadSummary(id, trackId, DownloadState.QUEUED))
        scheduler.enqueue(id)
    }

    private suspend fun transition(
        id: DownloadId,
        target: DownloadState,
        afterPersist: suspend () -> Unit,
    ) {
        val current = records.get(id) ?: return
        if (!DownloadStateMachine.canTransition(current.state, target)) return
        records.replace(
            current.copy(
                state = target,
                errorMessage = if (target == DownloadState.QUEUED) null else current.errorMessage,
            ),
        )
        afterPersist()
    }
}
