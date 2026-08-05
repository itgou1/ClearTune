package com.cleartune.feature.library

import com.cleartune.core.contracts.LibraryRepository
import com.cleartune.core.model.Album
import com.cleartune.core.model.AlbumId
import com.cleartune.core.model.Artist
import com.cleartune.core.model.ArtistId
import com.cleartune.core.model.LibraryHome
import com.cleartune.core.model.SearchResults
import com.cleartune.core.model.SongQuery
import com.cleartune.core.model.TrackId
import com.cleartune.core.model.TrackSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryBrowseStateTest {
    @Test
    fun every_browse_route_receives_live_non_placeholder_data() = runBlocking {
        val repository = FakeLibraryRepository()
        val port = FakeLibraryBrowsePort()
        val state = LibraryBrowseState(repository, port)
        val album = album("album-1", "First album")
        val artist = artist("artist-1", "First artist")
        val albumTrack = track("track-1", "Album track")
        val artistTrack = track("track-2", "Artist track")
        val artistAlbum = album("album-2", "Artist album")
        val folderTrack = track("track-3", "Folder track")
        port.albums.value = listOf(album)
        port.artists.value = listOf(artist)
        port.folders.value = listOf(LibraryFolderUi("Music/Live", 1))
        repository.albumTracks.value = listOf(albumTrack)
        port.artistTracks.value = listOf(artistTrack)
        port.artistAlbums.value = listOf(artistAlbum)
        port.folderTracks.value = listOf(folderTrack)

        assertEquals(listOf(album), state.albums.first())
        assertEquals(listOf(artist), state.artists.first())
        assertEquals(listOf(LibraryFolderUi("Music/Live", 1)), state.folders.first())
        assertEquals(LibraryAlbumDetailState(album, listOf(albumTrack)), state.albumDetail(album).first())
        assertEquals(
            LibraryArtistDetailState(artist, listOf(artistTrack), listOf(artistAlbum)),
            state.artistDetail(artist).first(),
        )
        assertEquals(
            LibraryFolderBrowseState(
                listOf(LibraryFolderUi("Music/Live", 1)),
                LibraryFolderUi("Music/Live", 1),
                listOf(folderTrack),
            ),
            state.folder("Music/Live").first(),
        )
    }

    @Test
    fun browse_routes_update_when_repository_flows_change() = runBlocking {
        val repository = FakeLibraryRepository()
        val port = FakeLibraryBrowsePort()
        val state = LibraryBrowseState(repository, port)
        val album = album("album-1", "First album")
        val artist = artist("artist-1", "First artist")
        val albumDetail = state.albumDetail(album)
        val artistDetail = state.artistDetail(artist)
        val folderDetail = state.folder("Music/Updated")

        assertEquals(emptyList<Album>(), state.albums.first())
        assertEquals(emptyList<Artist>(), state.artists.first())
        assertEquals(emptyList<LibraryFolderUi>(), state.folders.first())
        assertEquals(emptyList<TrackSummary>(), albumDetail.first().tracks)
        assertEquals(emptyList<TrackSummary>(), artistDetail.first().tracks)
        assertEquals(emptyList<Album>(), artistDetail.first().albums)
        assertEquals(emptyList<TrackSummary>(), folderDetail.first().tracks)

        port.albums.value = listOf(album("album-2", "Updated album"))
        port.artists.value = listOf(artist("artist-2", "Updated artist"))
        port.folders.value = listOf(LibraryFolderUi("Music/Updated", 2))
        repository.albumTracks.value = listOf(track("track-4", "Updated album track"))
        port.artistTracks.value = listOf(track("track-5", "Updated artist track"))
        port.artistAlbums.value = listOf(album("album-3", "Updated artist album"))
        port.folderTracks.value = listOf(track("track-6", "Updated folder track"))

        assertEquals("Updated album", state.albums.first().single().title)
        assertEquals("Updated artist", state.artists.first().single().name)
        assertEquals("Music/Updated", state.folders.first().single().path)
        assertEquals("Updated album track", albumDetail.first().tracks.single().title)
        assertEquals("Updated artist track", artistDetail.first().tracks.single().title)
        assertEquals("Updated artist album", artistDetail.first().albums.single().title)
        assertEquals("Updated folder track", folderDetail.first().tracks.single().title)
    }

    private fun album(id: String, title: String) = Album(AlbumId(id), title)
    private fun artist(id: String, name: String) = Artist(ArtistId(id), name)
    private fun track(id: String, title: String) = TrackSummary(TrackId(id), title)
}

private class FakeLibraryRepository : LibraryRepository {
    val albumTracks = MutableStateFlow<List<TrackSummary>>(emptyList())

    override fun observeLibraryHome(): Flow<LibraryHome> = MutableStateFlow(LibraryHome())
    override fun observeSongs(query: SongQuery): Flow<List<TrackSummary>> = MutableStateFlow(emptyList())
    override fun observeAlbumTracks(albumId: AlbumId): Flow<List<TrackSummary>> = albumTracks
    override fun search(query: String): Flow<SearchResults> = MutableStateFlow(SearchResults())
}

private class FakeLibraryBrowsePort : LibraryBrowsePort {
    val albums = MutableStateFlow<List<Album>>(emptyList())
    val artists = MutableStateFlow<List<Artist>>(emptyList())
    val folders = MutableStateFlow<List<LibraryFolderUi>>(emptyList())
    val artistTracks = MutableStateFlow<List<TrackSummary>>(emptyList())
    val artistAlbums = MutableStateFlow<List<Album>>(emptyList())
    val folderTracks = MutableStateFlow<List<TrackSummary>>(emptyList())

    override fun observeAlbums(): Flow<List<Album>> = albums
    override fun observeArtists(): Flow<List<Artist>> = artists
    override fun observeFolders(): Flow<List<LibraryFolderUi>> = folders
    override fun observeArtistTracks(artistId: ArtistId): Flow<List<TrackSummary>> = artistTracks
    override fun observeArtistAlbums(artistId: ArtistId): Flow<List<Album>> = artistAlbums
    override fun observeFolderTracks(path: String): Flow<List<TrackSummary>> = folderTracks
}
