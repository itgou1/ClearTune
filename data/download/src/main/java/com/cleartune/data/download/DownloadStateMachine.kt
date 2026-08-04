package com.cleartune.data.download

import com.cleartune.core.model.DownloadState

object DownloadStateMachine {
    fun canTransition(from: DownloadState, to: DownloadState): Boolean = when (from) {
        DownloadState.QUEUED -> to in setOf(
            DownloadState.RUNNING,
            DownloadState.PAUSED,
            DownloadState.FAILED,
            DownloadState.CANCELED,
        )
        DownloadState.RUNNING -> to in setOf(
            DownloadState.PAUSED,
            DownloadState.COMPLETED,
            DownloadState.FAILED,
            DownloadState.CANCELED,
        )
        DownloadState.PAUSED -> to in setOf(DownloadState.QUEUED, DownloadState.CANCELED)
        DownloadState.FAILED -> to in setOf(DownloadState.QUEUED, DownloadState.CANCELED)
        DownloadState.COMPLETED -> to in setOf(DownloadState.UPDATE_AVAILABLE, DownloadState.CANCELED)
        DownloadState.UPDATE_AVAILABLE -> to in setOf(DownloadState.QUEUED, DownloadState.CANCELED)
        DownloadState.CANCELED -> false
    }
}
