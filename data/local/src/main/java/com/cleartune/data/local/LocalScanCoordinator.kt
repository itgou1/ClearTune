package com.cleartune.data.local

import com.cleartune.core.database.LibrarySnapshotStore
import com.cleartune.core.database.model.LibraryIngestRecord
import com.cleartune.core.model.MutationResult
import com.cleartune.core.model.SourceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class LocalScanResult(
    val outcome: LocalScanOutcome,
    val mutation: MutationResult = MutationResult(),
    val warnings: List<String> = emptyList(),
    val errorMessage: String? = null,
)

class LocalScanCoordinator(
    private val gateway: MediaStoreGateway,
    private val snapshotStore: LibrarySnapshotStore,
    private val scanEngine: LocalScanEngine = LocalScanEngine(),
    private val sourceId: SourceId = LOCAL_SOURCE_ID,
    private val sourceName: String = "本地音乐",
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(LocalScanState())
    val state: StateFlow<LocalScanState> = mutableState.asStateFlow()

    suspend fun scan(permissionGranted: Boolean): LocalScanResult = mutex.withLock {
        if (!permissionGranted) {
            mutableState.value = LocalScanState(phase = LocalScanPhase.PERMISSION_REQUIRED)
            return@withLock LocalScanResult(LocalScanOutcome.PERMISSION_REQUIRED)
        }
        try {
            mutableState.value = LocalScanState(phase = LocalScanPhase.READING)
            val readResult = gateway.readAudio()
            val prepared = scanEngine.diff(emptyList(), readResult.snapshots)
            val warnings = readResult.warnings + prepared.warnings
            mutableState.value = LocalScanState(
                phase = LocalScanPhase.APPLYING,
                processed = 0,
                total = prepared.accepted.size,
                warningCount = warnings.size,
            )
            val now = clock()
            val mutation = snapshotStore.applyLocalSnapshot(
                sourceId = sourceId,
                sourceName = sourceName,
                records = prepared.accepted.map { snapshot -> snapshot.toIngestRecord(now) },
                syncedAtEpochMs = now,
            )
            mutableState.value = LocalScanState(
                phase = LocalScanPhase.COMPLETED,
                processed = prepared.accepted.size,
                total = prepared.accepted.size,
                warningCount = warnings.size,
            )
            LocalScanResult(LocalScanOutcome.COMPLETED, mutation, warnings)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (denied: SecurityException) {
            mutableState.value = LocalScanState(phase = LocalScanPhase.PERMISSION_REQUIRED)
            LocalScanResult(LocalScanOutcome.PERMISSION_REQUIRED)
        } catch (failure: Exception) {
            val safeMessage = failure.message?.take(160) ?: failure.javaClass.simpleName
            mutableState.value = LocalScanState(phase = LocalScanPhase.FAILED, errorMessage = safeMessage)
            LocalScanResult(LocalScanOutcome.FAILED, errorMessage = safeMessage)
        }
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
