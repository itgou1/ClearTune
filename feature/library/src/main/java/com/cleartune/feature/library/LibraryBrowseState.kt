package com.cleartune.feature.library

import com.cleartune.core.contracts.LibraryRepository
import com.cleartune.core.model.Album
import com.cleartune.core.model.Artist
import com.cleartune.core.model.ArtistId
import com.cleartune.core.model.TrackSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

interface LibraryBrowsePort {
    fun observeAlbums(): Flow<List<Album>>
    fun observeArtists(): Flow<List<Artist>>
    fun observeFolders(): Flow<List<LibraryFolderUi>>
    fun observeArtistTracks(artistId: ArtistId): Flow<List<TrackSummary>>
    fun observeArtistAlbums(artistId: ArtistId): Flow<List<Album>>
    fun observeFolderTracks(path: String): Flow<List<TrackSummary>>
    fun observeFolderTracks(folder: LibraryFolderUi): Flow<List<TrackSummary>> = observeFolderTracks(folder.path)
}

data class LibraryAlbumDetailState(
    val album: Album?,
    val tracks: List<TrackSummary>,
)

data class LibraryArtistDetailState(
    val artist: Artist?,
    val tracks: List<TrackSummary>,
    val albums: List<Album>,
)

data class LibraryFolderBrowseState(
    val folders: List<LibraryFolderUi>,
    val selectedFolder: LibraryFolderUi?,
    val tracks: List<TrackSummary>,
)

class LibraryBrowseState(
    private val repository: LibraryRepository,
    private val port: LibraryBrowsePort,
) {
    val albums: Flow<List<Album>> = port.observeAlbums()
    val artists: Flow<List<Artist>> = port.observeArtists()
    val folders: Flow<List<LibraryFolderUi>> = port.observeFolders()

    fun albumDetail(album: Album?): Flow<LibraryAlbumDetailState> = if (album == null) {
        flowOf(LibraryAlbumDetailState(null, emptyList()))
    } else {
        repository.observeAlbumTracks(album.id).map { tracks -> LibraryAlbumDetailState(album, tracks) }
    }

    fun artistDetail(artist: Artist?): Flow<LibraryArtistDetailState> = if (artist == null) {
        flowOf(LibraryArtistDetailState(null, emptyList(), emptyList()))
    } else {
        combine(
            port.observeArtistTracks(artist.id),
            port.observeArtistAlbums(artist.id),
        ) { tracks, albums ->
            LibraryArtistDetailState(artist, tracks, albums)
        }
    }

    fun folder(selectedFolderKey: String?): Flow<LibraryFolderBrowseState> {
        val selectedReference = selectedFolderKey?.toFolderReference()
        val tracks = selectedReference?.let(port::observeFolderTracks) ?: flowOf(emptyList())
        return combine(folders, tracks) { availableFolders, folderTracks ->
            val selectedFolder = selectedFolderKey?.let { key ->
                availableFolders.firstOrNull { it.routeKey == key }
            }
            LibraryFolderBrowseState(availableFolders, selectedFolder, folderTracks)
        }
    }
}

private fun String.toFolderReference(): LibraryFolderUi {
    val separator = indexOf('\u001f')
    return if (separator < 0) LibraryFolderUi(this, 0) else LibraryFolderUi(
        path = substring(separator + 1),
        trackCount = 0,
        sourceId = com.cleartune.core.model.SourceId(substring(0, separator)),
    )
}
