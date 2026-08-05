package com.cleartune.feature.library

import com.cleartune.core.contracts.PlaybackGateway
import com.cleartune.core.contracts.QueueRepository
import com.cleartune.core.model.PlaybackCommand
import com.cleartune.core.model.PlaylistSummary
import com.cleartune.core.model.QueueCommand
import com.cleartune.core.model.TrackId

enum class TrackAction { QUEUE_NEXT, QUEUE_LAST, PLAYLIST, FAVORITE, DOWNLOAD, DETAILS }

fun selectablePlaylists(playlists: List<PlaylistSummary>): List<PlaylistSummary> =
    playlists.distinctBy { it.id }.sortedBy { it.name.lowercase() }

class LibraryPlaybackActions(
    private val queueRepository: QueueRepository,
    private val playbackGateway: PlaybackGateway,
    private val onQueueChanged: suspend () -> Unit,
) {
    suspend fun play(trackIds: List<TrackId>, startIndex: Int = 0) {
        if (trackIds.isEmpty()) return
        require(startIndex in trackIds.indices)
        queueRepository.apply(QueueCommand.Replace(trackIds, startIndex))
        onQueueChanged()
        playbackGateway.dispatch(PlaybackCommand.Play)
    }

    suspend fun addAll(trackIds: List<TrackId>) {
        trackIds.forEach { queueRepository.apply(QueueCommand.AddLast(it)) }
        if (trackIds.isNotEmpty()) onQueueChanged()
    }
}
