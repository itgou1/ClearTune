package com.cleartune.app

import com.cleartune.core.database.LibrarySnapshotStore
import com.cleartune.core.database.SyncSessionStore
import com.cleartune.core.database.model.LibraryIngestRecord
import com.cleartune.core.model.MutationResult
import com.cleartune.data.local.LocalAudioSnapshot
import com.cleartune.data.local.LocalScanProgress
import com.cleartune.data.local.LocalSnapshotPort
import com.cleartune.data.local.LocalSnapshotRequest

class LocalSnapshotAdapter(
    private val snapshots: LibrarySnapshotStore,
    private val sessions: SyncSessionStore,
) : LocalSnapshotPort {
    override suspend fun applySnapshot(
        request: LocalSnapshotRequest,
        onProgress: suspend (processed: Int) -> Unit,
    ): MutationResult {
        val records = request.records.map(LocalAudioSnapshot::toIngestRecord)
        val result = snapshots.applyLocalSnapshot(
            sourceId = request.sourceId,
            sourceName = request.sourceName,
            records = records,
            syncedAtEpochMs = request.syncedAtEpochMs,
            warningCount = request.warningCount,
            retainedSourceKeys = request.retainedSourceKeys,
        )
        if (records.isNotEmpty()) {
            var processed = 0
            while (processed < records.size) {
                processed = (processed + request.batchSize).coerceAtMost(records.size)
                onProgress(processed)
            }
        }
        return result
    }

    override suspend fun reportProgress(progress: LocalScanProgress) {
        sessions.recordSyncSession(
            sessionId = progress.sessionId,
            sourceId = progress.sourceId,
            startedAtEpochMs = progress.startedAtEpochMs,
            completedAtEpochMs = progress.completedAtEpochMs,
            phase = progress.state.phase.name,
            processed = progress.state.processed,
            total = progress.state.total,
            warningCount = progress.state.warningCount,
            errorMessage = progress.state.errorMessage,
        )
    }
}

private fun LocalAudioSnapshot.toIngestRecord() = LibraryIngestRecord(
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
    addedAtEpochMs = modifiedEpochSeconds.coerceAtLeast(0) * 1_000,
)
