package com.cleartune.data.local

import android.Manifest

object AudioPermissionPolicy {
    fun requiredPermission(sdkInt: Int): String = if (sdkInt >= 33) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
}

enum class LocalAudioPermissionState {
    NOT_REQUESTED,
    GRANTED,
    DENIED,
    PERMANENTLY_DENIED,
    UNAVAILABLE,
}

data class LocalAudioSnapshot(
    val sourceKey: String,
    val contentUri: String,
    val displayName: String,
    val relativeFolder: String,
    val title: String,
    val album: String?,
    val artistNames: List<String>,
    val durationMs: Long?,
    val sizeBytes: Long,
    val modifiedEpochSeconds: Long,
)

data class MediaStoreRow(
    val id: Long,
    val displayName: String?,
    val relativePath: String?,
    val dataPath: String?,
    val title: String?,
    val album: String?,
    val artist: String?,
    val durationMs: Long?,
    val sizeBytes: Long,
    val modifiedEpochSeconds: Long,
    val mimeType: String?,
)

data class MediaStoreReadResult(
    val snapshots: List<LocalAudioSnapshot>,
    val warnings: List<String> = emptyList(),
    val observedSourceKeys: Set<String> = snapshots.mapTo(linkedSetOf(), LocalAudioSnapshot::sourceKey),
    val isComplete: Boolean = true,
)

fun interface MediaStoreGateway {
    suspend fun readAudio(): MediaStoreReadResult
}

data class LocalScanDiff(
    val accepted: List<LocalAudioSnapshot>,
    val added: List<LocalAudioSnapshot>,
    val updated: List<LocalAudioSnapshot>,
    val removedSourceKeys: Set<String>,
    val unchangedCount: Int,
    val warnings: List<String>,
)

enum class LocalScanOutcome {
    COMPLETED,
    PERMISSION_REQUIRED,
    FAILED,
}

enum class LocalScanPhase {
    IDLE,
    READING,
    APPLYING,
    COMPLETED,
    PERMISSION_REQUIRED,
    FAILED,
}

data class LocalScanState(
    val phase: LocalScanPhase = LocalScanPhase.IDLE,
    val processed: Int = 0,
    val total: Int = 0,
    val warningCount: Int = 0,
    val errorMessage: String? = null,
)
