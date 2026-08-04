package com.cleartune.feature.player

import com.cleartune.core.model.PlaybackState
import kotlin.math.max

data class PlayerUiState(
    val progress: Float,
    val positionLabel: String,
    val durationLabel: String,
)

fun PlaybackState.toPlayerUiState(): PlayerUiState {
    val knownDuration = durationMs?.takeIf { it > 0 }
    val progress = if (knownDuration == null) 0f else (positionMs.toFloat() / knownDuration).coerceIn(0f, 1f)
    return PlayerUiState(
        progress = progress,
        positionLabel = formatDuration(positionMs),
        durationLabel = knownDuration?.let(::formatDuration) ?: "--:--",
    )
}

private fun formatDuration(milliseconds: Long): String {
    val totalSeconds = max(0, milliseconds) / 1_000
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}
