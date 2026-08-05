package com.cleartune.data.download

import com.cleartune.core.model.DownloadState

object DownloadStateMachine {
    fun canTransition(from: DownloadState, to: DownloadState): Boolean = when (from) {
        DownloadState.QUEUED, DownloadState.WAITING_FOR_WIFI -> to in setOf(
            DownloadState.RUNNING,
            DownloadState.WAITING_FOR_WIFI,
            DownloadState.QUEUED,
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
        DownloadState.PAUSED -> to in setOf(DownloadState.QUEUED, DownloadState.WAITING_FOR_WIFI, DownloadState.CANCELED)
        DownloadState.FAILED -> to in setOf(DownloadState.QUEUED, DownloadState.WAITING_FOR_WIFI, DownloadState.CANCELED)
        DownloadState.COMPLETED -> to in setOf(DownloadState.UPDATE_AVAILABLE, DownloadState.CANCELED)
        DownloadState.UPDATE_AVAILABLE -> to in setOf(DownloadState.QUEUED, DownloadState.WAITING_FOR_WIFI, DownloadState.CANCELED)
        DownloadState.CANCELED -> false
    }
}
