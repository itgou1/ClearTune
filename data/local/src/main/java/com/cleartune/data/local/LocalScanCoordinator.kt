package com.cleartune.data.local

import com.cleartune.core.database.LibrarySnapshotStore
import com.cleartune.core.database.SyncSessionStore
import com.cleartune.core.database.model.LibraryIngestRecord
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

data class LocalScanResult(
    val outcome: LocalScanOutcome,
    val mutation: MutationResult = MutationResult(),
    val warnings: List<String> = emptyList(),
    val errorMessage: String? = null,
)

class LocalScanCoordinator(
    private val gateway: MediaStoreGateway,
    private val snapshotStore: LibrarySnapshotStore,
    private val sessionStore: SyncSessionStore? = snapshotStore as? SyncSessionStore,
    private val scanEngine: LocalScanEngine = LocalScanEngine(),
    private val sourceId: SourceId = LOCAL_SOURCE_ID,
    private val sourceName: String = "本地音乐",
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(LocalScanState())
    val state: StateFlow<LocalScanState> = mutableState.asStateFlow()

    suspend fun scan(permissionGranted: Boolean): LocalScanResult = mutex.withLock {
        val startedAt = clock()
        val sessionId = "${sourceId.value}:$startedAt"
        if (!permissionGranted) {
            mutableState.value = LocalScanState(phase = LocalScanPhase.PERMISSION_REQUIRED)
            recordSession(sessionId, startedAt, "PERMISSION_REQUIRED", completedAt = startedAt)
            return@withLock LocalScanResult(LocalScanOutcome.PERMISSION_REQUIRED)
        }
        try {
            recordSession(sessionId, startedAt, "RUNNING")
            mutableState.value = LocalScanState(phase = LocalScanPhase.READING)
            val readResult = gateway.readAudio()
            if (!readResult.isComplete) {
                val message = readResult.warnings.firstOrNull() ?: "MediaStore read was incomplete"
                mutableState.value = LocalScanState(phase = LocalScanPhase.FAILED, errorMessage = message)
                recordSession(
                    sessionId,
                    startedAt,
                    "FAILED",
                    completedAt = clock(),
                    warningCount = readResult.warnings.size,
                    errorMessage = message,
                )
                return@withLock LocalScanResult(
                    outcome = LocalScanOutcome.FAILED,
                    warnings = readResult.warnings,
                    errorMessage = message,
                )
            }
            val prepared = scanEngine.diff(emptyList(), readResult.snapshots)
            val warnings = readResult.warnings + prepared.warnings
            mutableState.value = LocalScanState(
                phase = LocalScanPhase.APPLYING,
                processed = 0,
                total = prepared.accepted.size,
                warningCount = warnings.size,
            )
            val now = startedAt
            val mutation = snapshotStore.applyLocalSnapshot(
                sourceId = sourceId,
                sourceName = sourceName,
                records = prepared.accepted.map { snapshot -> snapshot.toIngestRecord(now) },
                syncedAtEpochMs = now,
                warningCount = warnings.size,
                retainedSourceKeys = readResult.observedSourceKeys + prepared.accepted.map(LocalAudioSnapshot::sourceKey),
            )
            mutableState.value = LocalScanState(
                phase = LocalScanPhase.COMPLETED,
                processed = prepared.accepted.size,
                total = prepared.accepted.size,
                warningCount = warnings.size,
            )
            recordSession(
                sessionId,
                startedAt,
                "COMPLETED",
                completedAt = clock(),
                processed = prepared.accepted.size,
                total = prepared.accepted.size,
                warningCount = warnings.size,
            )
            LocalScanResult(LocalScanOutcome.COMPLETED, mutation, warnings)
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) {
                recordSession(sessionId, startedAt, "CANCELLED", completedAt = clock(), errorMessage = "Scan cancelled")
            }
            throw cancellation
        } catch (denied: SecurityException) {
            mutableState.value = LocalScanState(phase = LocalScanPhase.PERMISSION_REQUIRED)
            recordSession(
                sessionId,
                startedAt,
                "PERMISSION_REQUIRED",
                completedAt = clock(),
                errorMessage = denied.message?.take(160),
            )
            LocalScanResult(LocalScanOutcome.PERMISSION_REQUIRED)
        } catch (failure: Exception) {
            val safeMessage = failure.message?.take(160) ?: failure.javaClass.simpleName
            mutableState.value = LocalScanState(phase = LocalScanPhase.FAILED, errorMessage = safeMessage)
            recordSession(sessionId, startedAt, "FAILED", completedAt = clock(), errorMessage = safeMessage)
            LocalScanResult(LocalScanOutcome.FAILED, errorMessage = safeMessage)
        }
    }

    private suspend fun recordSession(
        sessionId: String,
        startedAt: Long,
        phase: String,
        completedAt: Long? = null,
        processed: Int = 0,
        total: Int = 0,
        warningCount: Int = 0,
        errorMessage: String? = null,
    ) {
        sessionStore?.recordSyncSession(
            sessionId = sessionId,
            sourceId = sourceId,
            startedAtEpochMs = startedAt,
            completedAtEpochMs = completedAt,
            phase = phase,
            processed = processed,
            total = total,
            warningCount = warningCount,
            errorMessage = errorMessage,
        )
    }

    companion object {
        val LOCAL_SOURCE_ID = SourceId("local")
    }
}

private fun LocalAudioSnapshot.toIngestRecord(addedAtEpochMs: Long): LibraryIngestRecord = LibraryIngestRecord(
    sourceKey = sourceKey,
    uri = contentUri,
    displayName = displayName,
    relativeFolder = relativeFolder,
    title = title,
    albumTitle = album,
    artistNames = artistNames,
    durationMs = durationMs,
    artworkRef = null,
    sizeBytes = sizeBytes,
    modifiedEpochSeconds = modifiedEpochSeconds,
    addedAtEpochMs = addedAtEpochMs,
)
