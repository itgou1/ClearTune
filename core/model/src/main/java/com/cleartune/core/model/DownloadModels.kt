package com.cleartune.core.model

enum class DownloadState {
    QUEUED,
    WAITING_FOR_WIFI,
    RUNNING,
    PAUSED,
    COMPLETED,
    UPDATE_AVAILABLE,
    FAILED,
    CANCELED,
}

data class DownloadSummary(
    val id: DownloadId,
    val trackId: TrackId,
    val state: DownloadState,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long? = null,
    val finalPath: String? = null,
    val errorMessage: String? = null,
) {
    init {
        require(bytesDownloaded >= 0)
        require(totalBytes == null || totalBytes >= bytesDownloaded)
        require(state != DownloadState.COMPLETED || !finalPath.isNullOrBlank())
    }
}

sealed interface DownloadCommand {
    data class Enqueue(val trackId: TrackId) : DownloadCommand
    data class Pause(val downloadId: DownloadId) : DownloadCommand
    data class Resume(val downloadId: DownloadId) : DownloadCommand
    data class Cancel(val downloadId: DownloadId) : DownloadCommand
    data class Delete(val downloadId: DownloadId) : DownloadCommand
    data class Retry(val downloadId: DownloadId) : DownloadCommand
}
