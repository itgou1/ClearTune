package com.cleartune.feature.library

import com.cleartune.core.contracts.LibraryRepository
import com.cleartune.core.contracts.PlaylistRepository
import com.cleartune.core.model.AlbumId
import com.cleartune.core.model.LibraryHome
import com.cleartune.core.model.MusicSource
import com.cleartune.core.model.PlaylistCommand
import com.cleartune.core.model.PlaylistId
import com.cleartune.core.model.PlaylistSummary
import com.cleartune.core.model.SearchResults
import com.cleartune.core.model.SongQuery
import com.cleartune.core.model.SongSort
import com.cleartune.core.model.SourceId
import com.cleartune.core.model.SourceType
import com.cleartune.core.model.TrackId
import com.cleartune.core.model.TrackSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryPrimaryActionsTest {
    @Test
    fun `source choices dispatch the selected source id into the song query`() {
        val local = source("local", "Local", SourceType.LOCAL)
        val home = source("home", "Home NAS", SourceType.WEBDAV)
        val archive = source("archive", "Archive", SourceType.WEBDAV)
        val repository = RecordingLibraryRepository()

        assertEquals(
            listOf(null, local.id, archive.id, home.id),
            librarySourceFilters(listOf(home, local, archive)).map(LibrarySourceFilter::sourceId),
        )

        observeLibrarySongs(
            repository = repository,
            sort = SongSort.ALBUM,
            ascending = false,
            sourceId = home.id,
            downloadedOnly = true,
        )

        assertEquals(
            SongQuery(sort = SongSort.ALBUM, ascending = false, sourceId = home.id, downloadedOnly = true),
            repository.queries.single(),
        )
    }

    @Test
    fun `batch playlist actions preserve every selected track id for existing and created playlists`() = runBlocking {
        val repository = RecordingPlaylistRepository()
        val actions = LibraryBatchActions(repository) { LibraryDownloadOutcome.Enqueued }
        val selected = listOf(TrackId("one"), TrackId("two"), TrackId("three"))

        val existing = actions.addToPlaylist(PlaylistId("existing"), selected)
        val created = actions.createPlaylistAndAdd("Road trip", selected)

        assertEquals(selected, existing.addedTrackIds)
        assertEquals(PlaylistId("created"), created.playlistId)
        assertEquals(selected, created.addedTrackIds)
        assertEquals(
            listOf(
                PlaylistCommand.AddTrack(PlaylistId("existing"), TrackId("one")),
                PlaylistCommand.AddTrack(PlaylistId("existing"), TrackId("two")),
                PlaylistCommand.AddTrack(PlaylistId("existing"), TrackId("three")),
                PlaylistCommand.Create("Road trip"),
                PlaylistCommand.AddTrack(PlaylistId("created"), TrackId("one")),
                PlaylistCommand.AddTrack(PlaylistId("created"), TrackId("two")),
                PlaylistCommand.AddTrack(PlaylistId("created"), TrackId("three")),
            ),
            repository.commands,
        )
    }

    @Test
    fun `mixed local and remote batch download returns a typed result for every track`() = runBlocking {
        val remote = TrackId("remote")
        val local = TrackId("local")
        val calls = mutableListOf<TrackId>()
        val actions = LibraryBatchActions(RecordingPlaylistRepository()) { trackId ->
            calls += trackId
            if (trackId == remote) LibraryDownloadOutcome.Enqueued
            else LibraryDownloadOutcome.Unavailable("This track is only available locally")
        }

        val result = actions.download(listOf(remote, local))

        assertEquals(listOf(remote, local), calls)
        assertEquals(
            listOf(
                LibraryBatchDownloadItem(remote, LibraryDownloadOutcome.Enqueued),
                LibraryBatchDownloadItem(local, LibraryDownloadOutcome.Unavailable("This track is only available locally")),
            ),
            result.items,
        )
    }

    @Test
    fun `whole folder download is visible and dispatches the full set only for WebDAV`() = runBlocking {
        val local = LibraryFolderUi(
            path = "Music/Local",
            trackCount = 1,
            sourceName = "Local",
            sourceId = SourceId("local"),
            sourceType = SourceType.LOCAL,
        )
        val webDav = LibraryFolderUi(
            path = "Music/Remote",
            trackCount = 3,
            sourceName = "Home NAS",
            sourceId = SourceId("home"),
            sourceType = SourceType.WEBDAV,
        )
        val ids = listOf(TrackId("one"), TrackId("two"), TrackId("three"))
        val dispatched = mutableListOf<TrackId>()
        val actions = LibraryBatchActions(RecordingPlaylistRepository()) { trackId ->
            dispatched += trackId
            LibraryDownloadOutcome.Enqueued
        }

        assertFalse(local.canDownloadFolder)
        assertTrue(webDav.canDownloadFolder)
        assertEquals(LibraryFolderDownloadResult.NotRemoteFolder, actions.downloadFolder(local, ids))
        assertTrue(dispatched.isEmpty())

        val result = actions.downloadFolder(webDav, ids) as LibraryFolderDownloadResult.Dispatched
        assertEquals(ids, result.result.items.map(LibraryBatchDownloadItem::trackId))
        assertEquals(ids, dispatched)
    }

    private fun source(id: String, name: String, type: SourceType) = MusicSource(SourceId(id), name, type)
}

private class RecordingLibraryRepository : LibraryRepository {
    val queries = mutableListOf<SongQuery>()
    override fun observeLibraryHome(): Flow<LibraryHome> = flowOf(LibraryHome())
    override fun observeSongs(query: SongQuery): Flow<List<TrackSummary>> {
        queries += query
        return flowOf(emptyList())
    }
    override fun observeAlbumTracks(albumId: AlbumId): Flow<List<TrackSummary>> = flowOf(emptyList())
    override fun search(query: String): Flow<SearchResults> = flowOf(SearchResults())
}

private class RecordingPlaylistRepository : PlaylistRepository {
    private val playlists = MutableStateFlow(listOf(PlaylistSummary(PlaylistId("existing"), "Existing")))
    val commands = mutableListOf<PlaylistCommand>()

    override fun observePlaylists(): Flow<List<PlaylistSummary>> = playlists

    override suspend fun apply(command: PlaylistCommand) {
        commands += command
        if (command is PlaylistCommand.Create) {
            playlists.value = playlists.value + PlaylistSummary(PlaylistId("created"), command.name.trim())
        }
    }
}
