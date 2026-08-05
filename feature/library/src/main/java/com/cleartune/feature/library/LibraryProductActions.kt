package com.cleartune.feature.library

import com.cleartune.core.contracts.LibraryRepository
import com.cleartune.core.contracts.PlaybackGateway
import com.cleartune.core.contracts.PlaylistRepository
import com.cleartune.core.contracts.QueueRepository
import com.cleartune.core.model.MusicSource
import com.cleartune.core.model.PlaybackCommand
import com.cleartune.core.model.PlaylistCommand
import com.cleartune.core.model.PlaylistId
import com.cleartune.core.model.PlaylistSummary
import com.cleartune.core.model.QueueCommand
import com.cleartune.core.model.SongQuery
import com.cleartune.core.model.SongSort
import com.cleartune.core.model.SourceId
import com.cleartune.core.model.SourceType
import com.cleartune.core.model.TrackId
import com.cleartune.core.model.TrackSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

enum class TrackAction { QUEUE_NEXT, QUEUE_LAST, PLAYLIST, FAVORITE, DOWNLOAD, DETAILS }

fun selectablePlaylists(playlists: List<PlaylistSummary>): List<PlaylistSummary> =
    playlists.distinctBy { it.id }.sortedBy { it.name.lowercase() }

data class LibrarySourceFilter(
    val sourceId: SourceId?,
    val label: String,
)

fun librarySourceFilters(sources: List<MusicSource>): List<LibrarySourceFilter> = buildList {
    add(LibrarySourceFilter(null, "All"))
    sources.asSequence()
        .filter { it.enabled && it.type == SourceType.LOCAL }
        .sortedBy { it.name.lowercase() }
        .firstOrNull()
        ?.let { add(LibrarySourceFilter(it.id, "Local")) }
    sources.asSequence()
        .filter { it.enabled && it.type == SourceType.WEBDAV }
        .sortedBy { it.name.lowercase() }
        .forEach { add(LibrarySourceFilter(it.id, it.name)) }
}

fun observeLibrarySongs(
    repository: LibraryRepository,
    sort: SongSort,
    ascending: Boolean,
    sourceId: SourceId?,
    downloadedOnly: Boolean,
): Flow<List<TrackSummary>> = repository.observeSongs(
    SongQuery(
        sort = sort,
        ascending = ascending,
        sourceId = sourceId,
        downloadedOnly = downloadedOnly,
    ),
)

sealed interface LibraryDownloadOutcome {
    data object Enqueued : LibraryDownloadOutcome
    data object AlreadyDownloaded : LibraryDownloadOutcome
    data class Unavailable(val reason: String) : LibraryDownloadOutcome
    data class Failed(val reason: String) : LibraryDownloadOutcome
}

data class LibraryBatchDownloadItem(
    val trackId: TrackId,
    val outcome: LibraryDownloadOutcome,
)

data class LibraryBatchDownloadResult(val items: List<LibraryBatchDownloadItem>) {
    val enqueuedCount: Int get() = items.count { it.outcome == LibraryDownloadOutcome.Enqueued }
    val skippedCount: Int get() = items.count {
        it.outcome is LibraryDownloadOutcome.AlreadyDownloaded || it.outcome is LibraryDownloadOutcome.Unavailable
    }
    val failedCount: Int get() = items.count { it.outcome is LibraryDownloadOutcome.Failed }
}

data class LibraryPlaylistBatchResult(
    val playlistId: PlaylistId,
    val addedTrackIds: List<TrackId>,
)

sealed interface LibraryFolderDownloadResult {
    data object NotRemoteFolder : LibraryFolderDownloadResult
    data class Dispatched(val result: LibraryBatchDownloadResult) : LibraryFolderDownloadResult
}

class LibraryBatchActions(
    private val playlistRepository: PlaylistRepository,
    private val downloadTrack: suspend (TrackId) -> LibraryDownloadOutcome,
) {
    suspend fun addToPlaylist(playlistId: PlaylistId, trackIds: List<TrackId>): LibraryPlaylistBatchResult {
        val selectedIds = trackIds.distinct()
        selectedIds.forEach { trackId ->
            playlistRepository.apply(PlaylistCommand.AddTrack(playlistId, trackId))
        }
        return LibraryPlaylistBatchResult(playlistId, selectedIds)
    }

    suspend fun createPlaylistAndAdd(name: String, trackIds: List<TrackId>): LibraryPlaylistBatchResult {
        val existingIds = playlistRepository.observePlaylists().first().mapTo(hashSetOf(), PlaylistSummary::id)
        playlistRepository.apply(PlaylistCommand.Create(name))
        val created = playlistRepository.observePlaylists().first { playlists ->
            playlists.any { it.id !in existingIds }
        }.first { it.id !in existingIds }
        return addToPlaylist(created.id, trackIds)
    }

    suspend fun download(trackIds: List<TrackId>): LibraryBatchDownloadResult = LibraryBatchDownloadResult(
        trackIds.distinct().map { trackId ->
            val outcome = try {
                downloadTrack(trackId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                LibraryDownloadOutcome.Failed(failure.message ?: "Download could not be started")
            }
            LibraryBatchDownloadItem(trackId, outcome)
        },
    )

    suspend fun downloadFolder(folder: LibraryFolderUi, trackIds: List<TrackId>): LibraryFolderDownloadResult =
        if (folder.canDownloadFolder) LibraryFolderDownloadResult.Dispatched(download(trackIds))
        else LibraryFolderDownloadResult.NotRemoteFolder
}

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
