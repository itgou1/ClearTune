package com.cleartune.feature.player

import com.cleartune.core.model.PlaybackState
import com.cleartune.core.model.QueueSnapshot
import kotlin.math.max

enum class PlayerErrorAction { RETRY }

data class PlayerErrorUi(
    val message: String,
    val action: PlayerErrorAction = PlayerErrorAction.RETRY,
    val retryAvailable: Boolean,
)

sealed interface ArtworkPresentation {
    data class Remote(val reference: String) : ArtworkPresentation
    data class Fallback(val monogram: String) : ArtworkPresentation
}

sealed interface LyricsUiState {
    data object Loading : LyricsUiState
    data class Available(val lines: List<String>) : LyricsUiState
    data object Unavailable : LyricsUiState
}

data class PlayerUiState(
    val progress: Float,
    val positionLabel: String,
    val durationLabel: String,
    val artwork: ArtworkPresentation,
    val error: PlayerErrorUi?,
)

fun PlaybackState.toPlayerUiState(retryAvailable: Boolean = false): PlayerUiState {
    val knownDuration = durationMs?.takeIf { it > 0 }
    val progress = if (knownDuration == null) 0f else (positionMs.toFloat() / knownDuration).coerceIn(0f, 1f)
    return PlayerUiState(
        progress = progress,
        positionLabel = formatDuration(positionMs),
        durationLabel = knownDuration?.let(::formatDuration) ?: "--:--",
        artwork = currentTrack?.artworkRef?.takeIf(String::isNotBlank)?.let(ArtworkPresentation::Remote)
            ?: ArtworkPresentation.Fallback(
                currentTrack?.title?.trim()?.firstOrNull()?.uppercase() ?: "♪",
            ),
        error = errorMessage?.takeIf(String::isNotBlank)?.let { PlayerErrorUi(it, retryAvailable = retryAvailable) },
    )
}

data class QueueRowUi(
    val stableKey: String,
    val title: String,
    val isCurrent: Boolean,
    val playActionLabel: String,
    val moveUpActionLabel: String,
    val moveDownActionLabel: String,
    val removeActionLabel: String,
)

fun QueueSnapshot.toQueueRows(titles: Map<com.cleartune.core.model.TrackId, String>): List<QueueRowUi> =
    items.mapIndexed { index, item ->
        val title = titles[item.trackId]?.takeIf(String::isNotBlank) ?: item.trackId.value
        QueueRowUi(
            stableKey = item.id.value,
            title = title,
            isCurrent = index == currentIndex,
            playActionLabel = "Play $title",
            moveUpActionLabel = "Move $title up",
            moveDownActionLabel = "Move $title down",
            removeActionLabel = "Remove $title from queue",
        )
    }

private fun formatDuration(milliseconds: Long): String {
    val totalSeconds = max(0, milliseconds) / 1_000
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}
