package com.cleartune.data.local

import com.cleartune.core.model.MutationResult
import com.cleartune.core.model.SourceId

data class LocalSnapshotRequest(
    val sourceId: SourceId,
    val sourceName: String,
    val records: List<LocalAudioSnapshot>,
    val syncedAtEpochMs: Long,
    val warningCount: Int,
    val retainedSourceKeys: Set<String>,
    val batchSize: Int,
) {
    init {
        require(batchSize > 0)
        require(warningCount >= 0)
    }
}

data class LocalScanProgress(
    val sessionId: String,
    val sourceId: SourceId,
    val startedAtEpochMs: Long,
    val completedAtEpochMs: Long? = null,
    val state: LocalScanState,
)

interface LocalSnapshotPort {
    suspend fun applySnapshot(
        request: LocalSnapshotRequest,
        onProgress: suspend (processed: Int) -> Unit,
    ): MutationResult

    suspend fun reportProgress(progress: LocalScanProgress)
}
