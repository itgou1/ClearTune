package com.cleartune.data.webdav

import com.cleartune.core.model.MusicSource
import com.cleartune.core.model.SourceId
import kotlinx.coroutines.CancellationException

data class RemoteFingerprint(
    val sizeBytes: Long?,
    val etag: String?,
    val available: Boolean = true,
)

data class WebDavSyncCheckpoint(
    val sourceId: SourceId,
    val pendingDirectories: List<String>,
    val visitedDirectories: Set<String> = emptySet(),
    val retainedSourceKeys: Set<String> = emptySet(),
    val discoveredTracks: Int = 0,
    val failures: List<SyncFailure> = emptyList(),
)

/** Persistence boundary implemented by the app database assembly. */
interface WebDavSyncPort {
    suspend fun loadSource(sourceId: SourceId): MusicSource?
    suspend fun loadCheckpoint(sourceId: SourceId): WebDavSyncCheckpoint?
    suspend fun saveCheckpoint(checkpoint: WebDavSyncCheckpoint)
    suspend fun clearCheckpoint(sourceId: SourceId)
    suspend fun remoteFingerprint(sourceId: SourceId, sourceKey: String): RemoteFingerprint?
    suspend fun markUpdateAvailable(sourceId: SourceId, sourceKey: String)
}

class WebDavSyncException(
    message: String,
    val retryable: Boolean,
) : Exception(message)

fun interface WebDavSyncOperation {
    suspend fun sync(
        source: MusicSource,
        checkpoint: WebDavSyncCheckpoint?,
        saveCheckpoint: suspend (WebDavSyncCheckpoint) -> Unit,
    ): WebDavSyncReport
}

fun interface WebDavSyncRunner {
    suspend fun run(sourceId: SourceId): WebDavWorkerOutcome
}

enum class WebDavWorkerOutcome { COMPLETED, RETRY, FAILED }

class DurableWebDavSyncRunner(
    private val port: WebDavSyncPort,
    private val operation: WebDavSyncOperation,
) : WebDavSyncRunner {
    override suspend fun run(sourceId: SourceId): WebDavWorkerOutcome {
        val source = port.loadSource(sourceId) ?: return WebDavWorkerOutcome.FAILED
        return try {
            val persisted = port.loadCheckpoint(sourceId)
            val checkpoint = if (persisted?.failures?.isNotEmpty() == true &&
                persisted.failures.none(SyncFailure::retryable)
            ) {
                port.clearCheckpoint(sourceId)
                null
            } else {
                persisted
            }
            val report = operation.sync(source, checkpoint, port::saveCheckpoint)
            when {
                report.failures.isEmpty() -> {
                    port.clearCheckpoint(sourceId)
                    WebDavWorkerOutcome.COMPLETED
                }
                report.failures.any(SyncFailure::retryable) -> WebDavWorkerOutcome.RETRY
                else -> WebDavWorkerOutcome.FAILED
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: WebDavSyncException) {
            if (failure.retryable) WebDavWorkerOutcome.RETRY else WebDavWorkerOutcome.FAILED
        } catch (_: Exception) {
            WebDavWorkerOutcome.FAILED
        }
    }
}
