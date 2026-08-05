package com.cleartune.app

import com.cleartune.core.model.LocationType
import com.cleartune.core.model.PlayableTrack
import com.cleartune.core.model.DownloadState
import com.cleartune.feature.library.LocalAccessUiState
import com.cleartune.data.local.LocalScanOutcome
import com.cleartune.data.local.LocalScanResult

sealed interface TrackDownloadCapability {
    val canDownload: Boolean
    val reason: String

    data object Available : TrackDownloadCapability { override val canDownload = true; override val reason = "" }
    data object Remove : TrackDownloadCapability { override val canDownload = true; override val reason = "" }
    data class Unavailable(override val reason: String) : TrackDownloadCapability {
        override val canDownload = false
        init { require(reason.isNotBlank()) }
    }

    companion object {
        fun from(track: PlayableTrack?, downloaded: Boolean = false): TrackDownloadCapability = when {
            downloaded -> Remove
            track?.locations.orEmpty().any { it.available && it.type == LocationType.REMOTE_URL } -> Available
            else -> Unavailable("This track is only available locally")
        }
    }
}

sealed interface TrackDownloadActionResult {
    data object Done : TrackDownloadActionResult
    data class Unavailable(val reason: String) : TrackDownloadActionResult
    data class Failed(val reason: String) : TrackDownloadActionResult
}

object AudioPermissionDecision {
    fun afterResult(granted: Boolean, requestWasMade: Boolean, shouldShowRationale: Boolean): LocalAccessUiState = when {
        granted -> LocalAccessUiState.GRANTED
        requestWasMade && !shouldShowRationale -> LocalAccessUiState.DENIED_PERMANENTLY
        else -> LocalAccessUiState.DENIED_CAN_ASK
    }
}

internal fun requestAudioPermission(
    recordRequest: () -> Unit,
    launchRequest: () -> Unit,
) {
    recordRequest()
    launchRequest()
}

internal fun canReconcileMissingDownloadWork(state: DownloadState): Boolean =
    state == DownloadState.QUEUED || state == DownloadState.WAITING_FOR_WIFI

object LocalScanTerminalGate {
    fun requireSuccess(result: LocalScanResult) {
        when (result.outcome) {
            LocalScanOutcome.COMPLETED -> Unit
            LocalScanOutcome.PERMISSION_REQUIRED ->
                throw IllegalStateException(result.errorMessage ?: "Audio permission required; grant access from Library")
            LocalScanOutcome.TRANSIENT_FAILURE,
            LocalScanOutcome.FAILED,
            -> throw IllegalStateException(result.errorMessage ?: "Library scan failed")
        }
    }
}
