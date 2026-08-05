package com.cleartune.app

import com.cleartune.core.model.LocationId
import com.cleartune.core.model.DownloadState
import com.cleartune.core.model.LocationType
import com.cleartune.core.model.PlayableTrack
import com.cleartune.core.model.SourceId
import com.cleartune.core.model.Track
import com.cleartune.core.model.TrackId
import com.cleartune.core.model.TrackLocation
import com.cleartune.feature.library.LocalAccessUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.cleartune.data.local.LocalScanOutcome
import com.cleartune.data.local.LocalScanResult

class TrackProductPoliciesTest {
    @Test
    fun `local-only track has typed unavailable download capability`() {
        val capability = TrackDownloadCapability.from(playable(LocationType.LOCAL_URI))

        assertTrue(capability is TrackDownloadCapability.Unavailable)
        assertFalse(capability.canDownload)
        assertTrue(capability.reason.isNotBlank())
    }

    @Test
    fun `active remote track can be downloaded and completed copy can be removed`() {
        assertEquals(TrackDownloadCapability.Available, TrackDownloadCapability.from(playable(LocationType.REMOTE_URL)))
        assertEquals(TrackDownloadCapability.Remove, TrackDownloadCapability.from(playable(LocationType.LOCAL_URI), true))
    }

    @Test
    fun `permission decision distinguishes rationale from permanent denial`() {
        assertEquals(
            LocalAccessUiState.DENIED_CAN_ASK,
            AudioPermissionDecision.afterResult(granted = false, requestWasMade = true, shouldShowRationale = true),
        )
        assertEquals(
            LocalAccessUiState.DENIED_PERMANENTLY,
            AudioPermissionDecision.afterResult(granted = false, requestWasMade = true, shouldShowRationale = false),
        )
    }

    @Test
    fun `every audio permission entry records the request before launch`() {
        val events = mutableListOf<String>()

        requestAudioPermission(
            recordRequest = { events += "recorded" },
            launchRequest = { events += "launched" },
        )

        assertEquals(listOf("recorded", "launched"), events)
    }

    @Test
    fun `missing remote work reconciles every schedulable waiting state`() {
        assertTrue(canReconcileMissingDownloadWork(DownloadState.QUEUED))
        assertTrue(canReconcileMissingDownloadWork(DownloadState.WAITING_FOR_WIFI))
        assertFalse(canReconcileMissingDownloadWork(DownloadState.RUNNING))
        assertFalse(canReconcileMissingDownloadWork(DownloadState.CANCELED))
    }

    @Test
    fun `settings scan waits for and validates the real terminal result`() {
        LocalScanTerminalGate.requireSuccess(LocalScanResult(LocalScanOutcome.COMPLETED))

        val failure = runCatching {
            LocalScanTerminalGate.requireSuccess(
                LocalScanResult(LocalScanOutcome.PERMISSION_REQUIRED, errorMessage = "permission required"),
            )
        }.exceptionOrNull()
        assertTrue(requireNotNull(requireNotNull(failure).message).contains("permission", ignoreCase = true))
    }

    private fun playable(type: LocationType) = PlayableTrack(
        Track(TrackId("track"), "Track"),
        listOf(TrackLocation(LocationId("location"), TrackId("track"), SourceId("source"), "song.flac", type, "uri")),
    )
}
