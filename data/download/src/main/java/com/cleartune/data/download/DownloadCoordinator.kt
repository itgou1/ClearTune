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
import kotlinx.coroutines.CancellationException
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
    val waitsForWifi: Boolean get() = false
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
            is DownloadCommand.Retry -> retry(command.downloadId)
            is DownloadCommand.Cancel -> cancel(command.downloadId)
            is DownloadCommand.Delete -> delete(command.downloadId)
        }
    }

    private suspend fun enqueue(trackId: TrackId) {
        val existing = records.findByTrack(trackId)
        val summary = when {
            existing == null -> {
                val id = DownloadId(
                    UUID.nameUUIDFromBytes("cleartune-download:${trackId.value}".toByteArray(StandardCharsets.UTF_8))
                        .toString(),
                )
                DownloadSummary(id, trackId, DownloadState.QUEUED).also { records.insert(it) }
            }
            existing.state == DownloadState.CANCELED -> existing.copy(
                state = DownloadState.QUEUED,
                bytesDownloaded = 0,
                totalBytes = null,
                finalPath = null,
                errorMessage = null,
            ).also { records.replace(it) }
            else -> return
        }
        schedule(summary)
    }

    private suspend fun retry(id: DownloadId) {
        val current = records.get(id) ?: return
        if (current.state != DownloadState.CANCELED) {
            transition(id, DownloadState.QUEUED) { scheduler.enqueue(id) }
            return
        }
        val revived = current.copy(
            state = DownloadState.QUEUED,
            bytesDownloaded = 0,
            totalBytes = null,
            finalPath = null,
            errorMessage = null,
        )
        records.replace(revived)
        schedule(revived)
    }

    private suspend fun schedule(summary: DownloadSummary) {
        try {
            scheduler.enqueue(summary.id)
            if (scheduler.waitsForWifi) {
                records.replace(summary.copy(state = DownloadState.WAITING_FOR_WIFI))
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            records.replace(summary.copy(state = DownloadState.FAILED, errorMessage = "Unable to schedule download"))
        }
    }

    private suspend fun cancel(id: DownloadId) {
        val current = records.get(id) ?: return
        if (!DownloadStateMachine.canTransition(current.state, DownloadState.CANCELED)) return
        val canceled = current.copy(state = DownloadState.CANCELED, errorMessage = null)
        records.replace(canceled)
        if (!stopForCleanup(id, canceled, "Unable to stop download; cleanup pending")) return
        try {
            scheduler.deleteFiles(id)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            records.replace(canceled.copy(errorMessage = "Download canceled; file cleanup pending"))
        }
    }

    private suspend fun delete(id: DownloadId) {
        val current = records.get(id) ?: return
        val canceled = current.copy(state = DownloadState.CANCELED, errorMessage = null)
        records.replace(canceled)
        if (!stopForCleanup(id, canceled, "Delete incomplete; unable to stop download")) return
        try {
            scheduler.deleteFiles(id)
            records.remove(id)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            records.replace(canceled.copy(errorMessage = "Delete incomplete; file cleanup pending"))
        }
    }

    private suspend fun stopForCleanup(id: DownloadId, canceled: DownloadSummary, error: String): Boolean = try {
        scheduler.stop(id)
        true
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        records.replace(canceled.copy(errorMessage = error))
        false
    }

    private suspend fun transition(
        id: DownloadId,
        target: DownloadState,
        afterPersist: suspend () -> Unit,
    ) {
        val current = records.get(id) ?: return
        if (!DownloadStateMachine.canTransition(current.state, target)) return
        val persisted = current.copy(
            state = target,
            errorMessage = if (target == DownloadState.QUEUED) null else current.errorMessage,
        )
        records.replace(persisted)
        try {
            afterPersist()
            if (target == DownloadState.QUEUED && scheduler.waitsForWifi) {
                records.replace(persisted.copy(state = DownloadState.WAITING_FOR_WIFI))
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            if (target == DownloadState.QUEUED) {
                records.replace(persisted.copy(state = DownloadState.FAILED, errorMessage = "Unable to schedule download"))
            } else {
                records.replace(current.copy(errorMessage = "Unable to update download"))
            }
        }
    }
}
