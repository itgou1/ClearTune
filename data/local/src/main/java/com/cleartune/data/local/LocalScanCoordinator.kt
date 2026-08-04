package com.cleartune.data.local

import com.cleartune.core.model.MutationResult
import com.cleartune.core.model.SourceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

data class LocalScanResult(
    val outcome: LocalScanOutcome,
    val mutation: MutationResult = MutationResult(),
    val warnings: List<String> = emptyList(),
    val errorMessage: String? = null,
)

class LocalScanCoordinator(
    private val gateway: MediaStoreGateway,
    private val snapshotPort: LocalSnapshotPort,
    private val scanEngine: LocalScanEngine = LocalScanEngine(),
    private val sourceId: SourceId = LOCAL_SOURCE_ID,
    internal val sourceName: String = "本地音乐",
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(LocalScanState())
    val state: StateFlow<LocalScanState> = mutableState.asStateFlow()

    suspend fun scan(permissionGranted: Boolean): LocalScanResult = mutex.withLock {
        val startedAt = clock()
        val sessionId = "${sourceId.value}:$startedAt"
        if (!permissionGranted) {
            publish(
                sessionId,
                startedAt,
                LocalScanState(phase = LocalScanPhase.PERMISSION_REQUIRED),
                completedAtEpochMs = startedAt,
            )
            return@withLock LocalScanResult(LocalScanOutcome.PERMISSION_REQUIRED)
        }
        try {
            publish(sessionId, startedAt, LocalScanState(phase = LocalScanPhase.READING))
            val readResult = gateway.readAudio()
            if (!readResult.isComplete) {
                val message = readResult.warnings.firstOrNull() ?: "MediaStore read was incomplete"
                val failedState = LocalScanState(
                    phase = LocalScanPhase.FAILED,
                    warningCount = readResult.warnings.size,
                    errorMessage = message,
                )
                publish(sessionId, startedAt, failedState, completedAtEpochMs = clock())
                return@withLock LocalScanResult(
                    outcome = LocalScanOutcome.FAILED,
                    warnings = readResult.warnings,
                    errorMessage = message,
                )
            }
            val prepared = scanEngine.diff(emptyList(), readResult.snapshots)
            val warnings = readResult.warnings + prepared.warnings
            val total = prepared.accepted.size
            var lastProcessed = 0
            publish(
                sessionId,
                startedAt,
                LocalScanState(
                    phase = LocalScanPhase.APPLYING,
                    processed = 0,
                    total = total,
                    warningCount = warnings.size,
                ),
            )
            val mutation = snapshotPort.applySnapshot(
                request = LocalSnapshotRequest(
                    sourceId = sourceId,
                    sourceName = sourceName,
                    records = prepared.accepted,
                    syncedAtEpochMs = startedAt,
                    warningCount = warnings.size,
                    retainedSourceKeys = readResult.observedSourceKeys +
                        prepared.accepted.map(LocalAudioSnapshot::sourceKey),
                    batchSize = SNAPSHOT_BATCH_SIZE,
                ),
                onProgress = { processed ->
                    val monotonicProcessed = processed.coerceIn(lastProcessed, total)
                    if (monotonicProcessed > lastProcessed) {
                        lastProcessed = monotonicProcessed
                        publish(
                            sessionId,
                            startedAt,
                            LocalScanState(
                                phase = LocalScanPhase.APPLYING,
                                processed = monotonicProcessed,
                                total = total,
                                warningCount = warnings.size,
                            ),
                        )
                    }
                },
            )
            if (lastProcessed < total) {
                lastProcessed = total
                publish(
                    sessionId,
                    startedAt,
                    LocalScanState(
                        phase = LocalScanPhase.APPLYING,
                        processed = lastProcessed,
                        total = total,
                        warningCount = warnings.size,
                    ),
                )
            }
            publish(
                sessionId,
                startedAt,
                LocalScanState(
                    phase = LocalScanPhase.COMPLETED,
                    processed = total,
                    total = total,
                    warningCount = warnings.size,
                ),
                completedAtEpochMs = clock(),
            )
            LocalScanResult(LocalScanOutcome.COMPLETED, mutation, warnings)
        } catch (cancellation: CancellationException) {
            try {
                withContext(NonCancellable) {
                    publish(
                        sessionId,
                        startedAt,
                        LocalScanState(phase = LocalScanPhase.CANCELLED, errorMessage = "Scan cancelled"),
                        completedAtEpochMs = clock(),
                    )
                }
            } catch (progressFailure: Exception) {
                cancellation.addSuppressed(progressFailure)
            }
            throw cancellation
        } catch (denied: SecurityException) {
            val message = denied.message?.take(ERROR_MESSAGE_LIMIT)
            publish(
                sessionId,
                startedAt,
                LocalScanState(phase = LocalScanPhase.PERMISSION_REQUIRED, errorMessage = message),
                completedAtEpochMs = clock(),
            )
            LocalScanResult(LocalScanOutcome.PERMISSION_REQUIRED, errorMessage = message)
        } catch (failure: IOException) {
            failedResult(sessionId, startedAt, failure, LocalScanOutcome.TRANSIENT_FAILURE)
        } catch (failure: Exception) {
            failedResult(sessionId, startedAt, failure, LocalScanOutcome.FAILED)
        }
    }

    private suspend fun failedResult(
        sessionId: String,
        startedAt: Long,
        failure: Exception,
        outcome: LocalScanOutcome,
    ): LocalScanResult {
        val safeMessage = failure.message?.take(ERROR_MESSAGE_LIMIT) ?: failure.javaClass.simpleName
        publish(
            sessionId,
            startedAt,
            LocalScanState(phase = LocalScanPhase.FAILED, errorMessage = safeMessage),
            completedAtEpochMs = clock(),
        )
        return LocalScanResult(outcome, errorMessage = safeMessage)
    }

    private suspend fun publish(
        sessionId: String,
        startedAtEpochMs: Long,
        state: LocalScanState,
        completedAtEpochMs: Long? = null,
    ) {
        mutableState.value = state
        snapshotPort.reportProgress(
            LocalScanProgress(
                sessionId = sessionId,
                sourceId = sourceId,
                startedAtEpochMs = startedAtEpochMs,
                completedAtEpochMs = completedAtEpochMs,
                state = state,
            ),
        )
    }

    companion object {
        val LOCAL_SOURCE_ID = SourceId("local")
        private const val SNAPSHOT_BATCH_SIZE = 100
        private const val ERROR_MESSAGE_LIMIT = 160
    }
}
