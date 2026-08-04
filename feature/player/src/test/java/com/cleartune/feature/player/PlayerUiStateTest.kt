package com.cleartune.feature.player

import com.cleartune.core.model.PlaybackState
import org.junit.Assert.assertEquals
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
}
