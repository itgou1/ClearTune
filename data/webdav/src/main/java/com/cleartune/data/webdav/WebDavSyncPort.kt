package com.cleartune.data.webdav

import com.cleartune.core.model.MusicSource
import com.cleartune.core.model.MutationDisposition
import com.cleartune.core.model.SourceId
import kotlinx.coroutines.CancellationException

data class RemoteFingerprint(
    val sizeBytes: Long?,
    val etag: String?,
    val available: Boolean = true,
    val modifiedEpochMs: Long? = null,
)

data class WebDavSyncCheckpoint(
    val sourceId: SourceId,
    val pendingDirectories: List<String>,
    val visitedDirectories: Set<String> = emptySet(),
    val retainedSourceKeys: Set<String> = emptySet(),
    val discoveredTracks: Int = 0,
    val failures: List<SyncFailure> = emptyList(),
)

enum class WebDavSyncPhase { RUNNING, RETRYING, COMPLETED, FAILED, CANCELED }

data class WebDavSyncSession(
    val id: String,
    val sourceId: SourceId,
    val startedAtEpochMs: Long,
    val completedAtEpochMs: Long? = null,
    val phase: WebDavSyncPhase,
    val discoveredTracks: Int = 0,
    val visitedDirectories: Int = 0,
    val failureCount: Int = 0,
    val errorMessage: String? = null,
)

/** Persistence boundary implemented by the app database assembly. */
interface WebDavSyncPort {
    suspend fun loadSource(sourceId: SourceId): MusicSource?
    suspend fun loadCheckpoint(sourceId: SourceId): WebDavSyncCheckpoint?
    suspend fun saveCheckpoint(checkpoint: WebDavSyncCheckpoint): MutationDisposition
    suspend fun clearCheckpoint(sourceId: SourceId)
    suspend fun retireCheckpoint(sourceId: SourceId)
    suspend fun remoteFingerprint(sourceId: SourceId, sourceKey: String): RemoteFingerprint?
    suspend fun markUpdateAvailable(sourceId: SourceId, sourceKey: String): MutationDisposition
    suspend fun recordSyncSession(session: WebDavSyncSession) = Unit
    suspend fun markSourceSynced(sourceId: SourceId, syncedAtEpochMs: Long): MutationDisposition =
        MutationDisposition.APPLIED
}

class WebDavSyncException(
    message: String,
    val retryable: Boolean,
) : Exception(message)

fun interface WebDavSyncOperation {
    suspend fun sync(
        source: MusicSource,
        checkpoint: WebDavSyncCheckpoint?,
        saveCheckpoint: suspend (WebDavSyncCheckpoint) -> MutationDisposition,
    ): WebDavSyncReport
}

fun interface WebDavSyncRunner {
    suspend fun run(sourceId: SourceId): WebDavWorkerOutcome
}

enum class WebDavWorkerOutcome { COMPLETED, RETRY, FAILED }

class DurableWebDavSyncRunner(
    private val port: WebDavSyncPort,
    private val clock: () -> Long = System::currentTimeMillis,
    private val operation: WebDavSyncOperation,
) : WebDavSyncRunner {
    override suspend fun run(sourceId: SourceId): WebDavWorkerOutcome {
        val source = port.loadSource(sourceId) ?: return WebDavWorkerOutcome.FAILED
        val startedAt = clock()
        val sessionId = "${sourceId.value}:webdav:$startedAt"
        suspend fun record(
            phase: WebDavSyncPhase,
            checkpoint: WebDavSyncCheckpoint? = null,
            report: WebDavSyncReport? = null,
            completedAt: Long? = null,
            error: String? = null,
        ) {
            port.recordSyncSession(
                WebDavSyncSession(
                    id = sessionId,
                    sourceId = sourceId,
                    startedAtEpochMs = startedAt,
                    completedAtEpochMs = completedAt,
                    phase = phase,
                    discoveredTracks = report?.discoveredTracks ?: checkpoint?.discoveredTracks ?: 0,
                    visitedDirectories = report?.visitedDirectories ?: checkpoint?.visitedDirectories?.size ?: 0,
                    failureCount = report?.failures?.size ?: checkpoint?.failures?.size ?: 0,
                    errorMessage = error,
                ),
            )
        }
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
            record(WebDavSyncPhase.RUNNING, checkpoint)
            val report = operation.sync(source, checkpoint) { next ->
                val disposition = port.saveCheckpoint(next)
                if (disposition == MutationDisposition.APPLIED) record(WebDavSyncPhase.RUNNING, next)
                disposition
            }
            when {
                report.retired -> {
                    port.retireCheckpoint(sourceId)
                    record(WebDavSyncPhase.CANCELED, report = report, completedAt = clock())
                    WebDavWorkerOutcome.COMPLETED
                }
                report.failures.isEmpty() -> {
                    port.clearCheckpoint(sourceId)
                    val completedAt = clock()
                    if (port.markSourceSynced(sourceId, completedAt) == MutationDisposition.SOURCE_RETIRED) {
                        record(WebDavSyncPhase.CANCELED, report = report, completedAt = completedAt)
                        return WebDavWorkerOutcome.COMPLETED
                    }
                    record(WebDavSyncPhase.COMPLETED, report = report, completedAt = completedAt)
                    WebDavWorkerOutcome.COMPLETED
                }
                report.failures.any(SyncFailure::retryable) -> {
                    record(WebDavSyncPhase.RETRYING, report = report, error = "Temporary sync failure")
                    WebDavWorkerOutcome.RETRY
                }
                else -> {
                    record(WebDavSyncPhase.FAILED, report = report, completedAt = clock(), error = "Sync failed")
                    WebDavWorkerOutcome.FAILED
                }
            }
        } catch (cancellation: CancellationException) {
            runCatching {
                record(WebDavSyncPhase.CANCELED, completedAt = clock(), error = "Sync canceled")
            }
            throw cancellation
        } catch (failure: WebDavSyncException) {
            if (failure.retryable) {
                runCatching { record(WebDavSyncPhase.RETRYING, error = "Temporary sync failure") }
                WebDavWorkerOutcome.RETRY
            } else {
                runCatching { record(WebDavSyncPhase.FAILED, completedAt = clock(), error = "Sync failed") }
                WebDavWorkerOutcome.FAILED
            }
        } catch (_: Exception) {
            runCatching { record(WebDavSyncPhase.FAILED, completedAt = clock(), error = "Sync failed") }
            WebDavWorkerOutcome.FAILED
        }
    }
}
