package com.cleartune.feature.player

import com.cleartune.core.model.PlaybackState
import com.cleartune.core.model.QueueItem
import com.cleartune.core.model.QueueItemId
import com.cleartune.core.model.QueueSnapshot
import com.cleartune.core.model.TrackId
import com.cleartune.core.model.TrackSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerUiStateTest {
    @Test
    fun `progress is clamped and durations are formatted`() {
        val uiState = PlaybackState(positionMs = 75_000, durationMs = 60_000).toPlayerUiState()

        assertEquals(1f, uiState.progress)
        assertEquals("1:15", uiState.positionLabel)
        assertEquals("1:00", uiState.durationLabel)
    }

    @Test
    fun `unknown duration has safe empty progress`() {
        val uiState = PlaybackState(positionMs = 5_000, durationMs = null).toPlayerUiState()

        assertEquals(0f, uiState.progress)
        assertEquals("--:--", uiState.durationLabel)
    }

    @Test
    fun `playback error exposes a retry action and artwork fallback`() {
        val uiState = PlaybackState(
            currentTrack = TrackSummary(TrackId("song-1"), "Saturn", artistNames = listOf("Nova")),
            errorMessage = "Network unavailable",
        ).toPlayerUiState()

        assertEquals("Network unavailable", uiState.error?.message)
        assertEquals(PlayerErrorAction.RETRY, uiState.error?.action)
        assertEquals(ArtworkPresentation.Fallback("S"), uiState.artwork)
    }

    @Test
    fun `unavailable location error only enables injected retry capability`() {
        val playback = PlaybackState(errorMessage = "No playable location")

        assertFalse(playback.toPlayerUiState(retryAvailable = false).error!!.retryAvailable)
        assertTrue(playback.toPlayerUiState(retryAvailable = true).error!!.retryAvailable)
    }

    @Test
    fun `download action is explicitly unavailable until a command is bound`() {
        val actions = PlayerTrackActionState()

        assertFalse(actions.canDownload)
        assertEquals("Download unavailable", actions.downloadLabel)
        assertTrue(actions.downloadUnavailableReason.isNotBlank())
    }

    @Test
    fun `queue rows preserve duplicate occurrences and expose explicit actions`() {
        val first = QueueItem(QueueItemId("occurrence-1"), TrackId("song"))
        val second = QueueItem(QueueItemId("occurrence-2"), TrackId("song"))

        val rows = QueueSnapshot(listOf(first, second), currentIndex = 1).toQueueRows(
            titles = mapOf(TrackId("song") to "Saturn"),
        )

        assertEquals(listOf("Saturn", "Saturn"), rows.map { it.title })
        assertNotEquals(rows[0].stableKey, rows[1].stableKey)
        assertEquals("Play Saturn", rows[0].playActionLabel)
        assertEquals("Move Saturn up", rows[0].moveUpActionLabel)
        assertEquals("Remove Saturn from queue", rows[0].removeActionLabel)
        assertTrue(rows[1].isCurrent)
    }
}
