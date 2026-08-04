package com.cleartune.feature.playlists

import com.cleartune.core.model.PlaylistCommand
import com.cleartune.core.model.PlaylistId
import com.cleartune.core.model.PlaylistItemId
import com.cleartune.core.model.TrackId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class InMemoryPlaylistRepositoryTest {
    private var nextId = 0
    private val repository = InMemoryPlaylistRepository {
        (++nextId).toString()
    }

    @Test
    fun `create trims names and rejects case insensitive duplicates`() = runTest {
        repository.apply(PlaylistCommand.Create("  Road Trip  "))

        assertEquals("Road Trip", repository.observePlaylists().first().single().name)
        try {
            repository.apply(PlaylistCommand.Create("road trip"))
            fail("Expected a duplicate-name failure")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `duplicate tracks keep unique occurrence ids and can be moved`() = runTest {
        repository.apply(PlaylistCommand.Create("Mix"))
        val playlistId = PlaylistId("1")
        val trackId = TrackId("track")
        repository.apply(PlaylistCommand.AddTrack(playlistId, trackId))
        repository.apply(PlaylistCommand.AddTrack(playlistId, trackId))

        val before = repository.observePlaylist(playlistId).first()!!.items
        repository.apply(PlaylistCommand.MoveTrack(playlistId, before[1].id, 0))
        val after = repository.observePlaylist(playlistId).first()!!.items

        assertEquals(2, after.map { it.id }.distinct().size)
        assertEquals(before[1].id, after[0].id)
    }

    @Test
    fun `remove track uses playlist item occurrence`() = runTest {
        repository.apply(PlaylistCommand.Create("Mix"))
        val playlistId = PlaylistId("1")
        repository.apply(PlaylistCommand.AddTrack(playlistId, TrackId("track")))
        val itemId: PlaylistItemId = repository.observePlaylist(playlistId).first()!!.items.single().id

        repository.apply(PlaylistCommand.RemoveTrack(playlistId, itemId))

        assertEquals(0, repository.observePlaylists().first().single().trackCount)
    }

    @Test
    fun `storage restores playlists after repository recreation`() = runTest {
        val storage = MemoryPlaylistStorage()
        val first = InMemoryPlaylistRepository(idFactory = { "id-${++nextId}" }, storage = storage)
        first.apply(PlaylistCommand.Create("Saved"))
        val playlistId = first.observePlaylists().first().single().id
        first.apply(PlaylistCommand.AddTrack(playlistId, TrackId("track")))

        val restored = InMemoryPlaylistRepository(storage = storage).observePlaylist(playlistId).first()

        assertEquals("Saved", restored?.name)
        assertEquals(listOf(TrackId("track")), restored?.items?.map { it.trackId })
    }
}

private class MemoryPlaylistStorage : PlaylistStorage {
    private var playlists: List<PlaylistDetails> = emptyList()
    override fun load(): List<PlaylistDetails> = playlists
    override fun save(playlists: List<PlaylistDetails>) { this.playlists = playlists }
}
