package com.cleartune.core.model

enum class RepeatMode { OFF, ALL, ONE }

data class QueueItem(
    val id: QueueItemId,
    val trackId: TrackId,
)

data class QueueSnapshot(
    val items: List<QueueItem> = emptyList(),
    val currentIndex: Int = -1,
    val positionMs: Long = 0,
    val playWhenReady: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val shuffleEnabled: Boolean = false,
) {
    init {
        require(positionMs >= 0)
        require(currentIndex in -1 until items.size || (items.isEmpty() && currentIndex == -1))
    }
}

data class PlaybackState(
    val connected: Boolean = false,
    val currentTrack: TrackSummary? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val shuffleEnabled: Boolean = false,
    val errorMessage: String? = null,
)

sealed interface PlaybackCommand {
    data class PlayTrack(val trackId: TrackId) : PlaybackCommand
    data object Play : PlaybackCommand
    data object Pause : PlaybackCommand
    data object Next : PlaybackCommand
    data object Previous : PlaybackCommand
    data class SeekTo(val positionMs: Long) : PlaybackCommand
    data class SetRepeat(val mode: RepeatMode) : PlaybackCommand
    data class SetShuffle(val enabled: Boolean) : PlaybackCommand
}

sealed interface QueueCommand {
    data class Replace(val trackIds: List<TrackId>, val startIndex: Int = 0) : QueueCommand
    data class AddNext(val trackId: TrackId) : QueueCommand
    data class AddLast(val trackId: TrackId) : QueueCommand
    data class Remove(val queueItemId: QueueItemId) : QueueCommand
    data class Move(val queueItemId: QueueItemId, val newIndex: Int) : QueueCommand
}
