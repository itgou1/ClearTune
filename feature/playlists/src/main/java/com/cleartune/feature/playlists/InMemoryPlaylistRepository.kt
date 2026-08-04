package com.cleartune.feature.playlists

import com.cleartune.core.contracts.PlaylistRepository
import com.cleartune.core.model.PlaylistCommand
import com.cleartune.core.model.PlaylistId
import com.cleartune.core.model.PlaylistItemId
import com.cleartune.core.model.PlaylistSummary
import com.cleartune.core.model.TrackId
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PlaylistItemRecord(val id: PlaylistItemId, val trackId: TrackId)

data class PlaylistDetails(
    val id: PlaylistId,
    val name: String,
    val items: List<PlaylistItemRecord>,
)

interface PlaylistDetailsProvider {
    fun observePlaylist(playlistId: PlaylistId): Flow<PlaylistDetails?>
}

interface PlaylistStorage {
    fun load(): List<PlaylistDetails>
    fun save(playlists: List<PlaylistDetails>)
}

class InMemoryPlaylistRepository(
    private val storage: PlaylistStorage? = null,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) : PlaylistRepository, PlaylistDetailsProvider {
    private val mutex = Mutex()
    private val playlists = MutableStateFlow(storage?.load().orEmpty())

    override fun observePlaylists(): Flow<List<PlaylistSummary>> = playlists.map { records ->
        records.map { PlaylistSummary(it.id, it.name, it.items.size) }
    }

    override fun observePlaylist(playlistId: PlaylistId): Flow<PlaylistDetails?> =
        playlists.map { records -> records.firstOrNull { it.id == playlistId } }

    override suspend fun apply(command: PlaylistCommand) = mutex.withLock {
        playlists.value = when (command) {
            is PlaylistCommand.Create -> {
                val name = validatedName(command.name, playlists.value)
                playlists.value + PlaylistDetails(PlaylistId(idFactory()), name, emptyList())
            }
            is PlaylistCommand.Rename -> playlists.value.map { playlist ->
                if (playlist.id != command.playlistId) playlist
                else playlist.copy(
                    name = validatedName(command.name, playlists.value.filterNot { it.id == playlist.id }),
                )
            }.also { requirePlaylist(command.playlistId) }
            is PlaylistCommand.Delete -> playlists.value.filterNot { it.id == command.playlistId }
                .also { requirePlaylist(command.playlistId) }
            is PlaylistCommand.AddTrack -> playlists.value.update(command.playlistId) { playlist ->
                playlist.copy(
                    items = playlist.items + PlaylistItemRecord(PlaylistItemId(idFactory()), command.trackId),
                )
            }
            is PlaylistCommand.RemoveTrack -> playlists.value.update(command.playlistId) { playlist ->
                require(playlist.items.any { it.id == command.playlistItemId }) { "Playlist item not found" }
                playlist.copy(items = playlist.items.filterNot { it.id == command.playlistItemId })
            }
            is PlaylistCommand.MoveTrack -> playlists.value.update(command.playlistId) { playlist ->
                val from = playlist.items.indexOfFirst { it.id == command.playlistItemId }
                require(from >= 0) { "Playlist item not found" }
                require(command.newIndex in playlist.items.indices) { "Playlist index out of range" }
                val mutable = playlist.items.toMutableList()
                val item = mutable.removeAt(from)
                mutable.add(command.newIndex, item)
                playlist.copy(items = mutable)
            }
        }
        storage?.save(playlists.value)
        Unit
    }

    private fun requirePlaylist(id: PlaylistId) {
        require(playlists.value.any { it.id == id }) { "Playlist not found" }
    }

    private fun validatedName(raw: String, existing: List<PlaylistDetails>): String {
        val name = raw.trim()
        require(name.length in 1..100) { "Playlist name must contain 1 to 100 characters" }
        require(existing.none { it.name.equals(name, ignoreCase = true) }) { "Playlist name already exists" }
        return name
    }
}

private inline fun List<PlaylistDetails>.update(
    id: PlaylistId,
    transform: (PlaylistDetails) -> PlaylistDetails,
): List<PlaylistDetails> {
    require(any { it.id == id }) { "Playlist not found" }
    return map { if (it.id == id) transform(it) else it }
}
