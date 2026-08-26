package com.cleartune.app.library

import com.cleartune.core.database.ClearTuneDatabase
import com.cleartune.core.database.PlaylistSongEntity
import com.cleartune.core.database.PendingMutationEntity
import com.cleartune.core.database.toEntity
import com.cleartune.core.database.toModel
import com.cleartune.core.datastore.CredentialsStore
import com.cleartune.core.model.Album
import com.cleartune.core.model.Artist
import com.cleartune.core.model.ClearTuneError
import com.cleartune.core.model.Playlist
import com.cleartune.core.model.Song
import com.cleartune.core.model.Lyrics
import com.cleartune.core.model.MusicDirectory
import com.cleartune.core.model.MusicFolder
import com.cleartune.core.network.LibraryRemoteDataSource
import com.cleartune.core.network.FavoriteTargetType
import com.cleartune.core.network.OpenSubsonicApiFactory
import com.cleartune.core.network.RemoteResult
import com.cleartune.core.network.SearchResults
import javax.inject.Inject
import javax.inject.Singleton
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

@Singleton
class MusicRepository @Inject constructor(
    private val credentialsStore: CredentialsStore,
    private val database: ClearTuneDatabase,
    private val apiFactory: OpenSubsonicApiFactory,
) {
    private val mediaDao = database.mediaDao()

    val albums: Flow<List<Album>> = mediaDao.observeAlbums().map { items -> items.map { it.toModel() } }
    val artists: Flow<List<Artist>> = mediaDao.observeArtists().map { items -> items.map { it.toModel() } }
    val songs: Flow<List<Song>> = mediaDao.observeSongs().map { items -> items.map { it.toModel() } }
    val playlists: Flow<List<Playlist>> = mediaDao.observePlaylists().map { items -> items.map { it.toModel() } }

    suspend fun refreshLibrary(): ClearTuneError? = coroutineScope {
        val remote = remote() ?: return@coroutineScope ClearTuneError.Authentication()
        val albumsRequest = async { loadAllPages { offset -> remote.albums(size = PAGE_SIZE, offset = offset) } }
        val artistsRequest = async { remote.artists() }
        val playlistsRequest = async { remote.playlists() }
        val songsRequest = async { loadAllPages { offset -> remote.songs(size = PAGE_SIZE, offset = offset) } }
        val favoritesRequest = async { remote.favorites() }
        val errors = mutableListOf<ClearTuneError>()

        when (val result = albumsRequest.await()) {
            is RemoteResult.Success -> mediaDao.upsertAlbums(result.value.map { it.toEntity() })
            is RemoteResult.Failure -> errors += result.error
        }
        when (val result = artistsRequest.await()) {
            is RemoteResult.Success -> mediaDao.upsertArtists(result.value.map { it.toEntity() })
            is RemoteResult.Failure -> errors += result.error
        }
        when (val result = playlistsRequest.await()) {
            is RemoteResult.Success -> mediaDao.upsertPlaylists(result.value.map { it.toEntity() })
            is RemoteResult.Failure -> errors += result.error
        }
        when (val result = songsRequest.await()) {
            is RemoteResult.Success -> mediaDao.upsertSongs(result.value.map { it.toEntity() })
            is RemoteResult.Failure -> errors += result.error
        }
        when (val result = favoritesRequest.await()) {
            is RemoteResult.Success -> {
                mediaDao.clearStarredFlags(System.currentTimeMillis())
                mediaDao.upsertSongs(result.value.songs.map { it.toEntity() })
                mediaDao.upsertAlbums(result.value.albums.map { it.toEntity() })
                mediaDao.upsertArtists(result.value.artists.map { it.toEntity() })
            }
            is RemoteResult.Failure -> Unit
        }
        syncPendingFavorites(remote)
        errors.firstOrNull()
    }

    suspend fun loadAlbum(id: String): ClearTuneError? {
        val remote = remote() ?: return ClearTuneError.Authentication()
        return when (val result = remote.album(id)) {
            is RemoteResult.Success -> {
                mediaDao.upsertAlbums(listOf(result.value.album.toEntity()))
                mediaDao.upsertSongs(result.value.songs.map { it.toEntity() })
                null
            }
            is RemoteResult.Failure -> result.error
        }
    }

    suspend fun loadArtist(id: String): ClearTuneError? {
        val remote = remote() ?: return ClearTuneError.Authentication()
        return when (val result = remote.artist(id)) {
            is RemoteResult.Success -> {
                mediaDao.upsertArtists(listOf(result.value.artist.toEntity()))
                mediaDao.upsertAlbums(result.value.albums.map { it.toEntity() })
                when (val songs = remote.topSongs(result.value.artist.name)) {
                    is RemoteResult.Success -> mediaDao.upsertSongs(songs.value.map { it.toEntity() })
                    is RemoteResult.Failure -> Unit
                }
                null
            }
            is RemoteResult.Failure -> result.error
        }
    }

    suspend fun loadPlaylist(id: String): ClearTuneError? {
        val remote = remote() ?: return ClearTuneError.Authentication()
        return when (val result = remote.playlist(id)) {
            is RemoteResult.Success -> {
                mediaDao.upsertPlaylists(listOf(result.value.playlist.toEntity()))
                mediaDao.upsertSongs(result.value.songs.map { it.toEntity() })
                mediaDao.replacePlaylistSongs(
                    id,
                    result.value.songs.mapIndexed { index, song ->
                        PlaylistSongEntity(id, song.id, index)
                    },
                )
                null
            }
            is RemoteResult.Failure -> result.error
        }
    }

    suspend fun createPlaylist(name: String): ClearTuneError? {
        val remote = remote() ?: return ClearTuneError.Authentication()
        return when (val result = remote.createPlaylist(name.trim())) {
            is RemoteResult.Success -> refreshPlaylists(remote)
            is RemoteResult.Failure -> result.error
        }
    }

    suspend fun renamePlaylist(id: String, name: String): ClearTuneError? {
        val remote = remote() ?: return ClearTuneError.Authentication()
        return when (val result = remote.renamePlaylist(id, name.trim())) {
            is RemoteResult.Success -> loadPlaylist(id)
            is RemoteResult.Failure -> result.error
        }
    }

    suspend fun addPlaylistSong(id: String, songId: String): ClearTuneError? {
        val remote = remote() ?: return ClearTuneError.Authentication()
        return when (val result = remote.addPlaylistSongs(id, listOf(songId))) {
            is RemoteResult.Success -> loadPlaylist(id)
            is RemoteResult.Failure -> result.error
        }
    }

    suspend fun removePlaylistSong(id: String, index: Int): ClearTuneError? {
        val remote = remote() ?: return ClearTuneError.Authentication()
        return when (val result = remote.removePlaylistSongs(id, listOf(index))) {
            is RemoteResult.Success -> loadPlaylist(id)
            is RemoteResult.Failure -> result.error
        }
    }

    suspend fun deletePlaylist(id: String): ClearTuneError? {
        val remote = remote() ?: return ClearTuneError.Authentication()
        return when (val result = remote.deletePlaylist(id)) {
            is RemoteResult.Success -> {
                mediaDao.clearPlaylistSongs(id)
                mediaDao.deletePlaylist(id)
                null
            }
            is RemoteResult.Failure -> result.error
        }
    }

    fun album(id: String): Flow<Album?> = mediaDao.observeAlbum(id).map { it?.toModel() }
    fun artist(id: String): Flow<Artist?> = mediaDao.observeArtist(id).map { it?.toModel() }
    fun playlist(id: String): Flow<Playlist?> = mediaDao.observePlaylist(id).map { it?.toModel() }
    fun albumSongs(id: String): Flow<List<Song>> = mediaDao.observeSongsForAlbum(id).map { list -> list.map { it.toModel() } }
    fun artistSongs(id: String): Flow<List<Song>> = mediaDao.observeSongsForArtist(id).map { list -> list.map { it.toModel() } }
    fun playlistSongs(id: String): Flow<List<Song>> = mediaDao.observePlaylistSongs(id).map { list -> list.map { it.toModel() } }

    suspend fun search(query: String): RemoteResult<SearchResults> {
        val remote = remote() ?: return RemoteResult.Failure(ClearTuneError.Authentication())
        val result = remote.search(query)
        if (result is RemoteResult.Success) {
            mediaDao.upsertArtists(result.value.artists.map { it.toEntity() })
            mediaDao.upsertAlbums(result.value.albums.map { it.toEntity() })
            mediaDao.upsertSongs(result.value.songs.map { it.toEntity() })
        }
        return when (result) {
            is RemoteResult.Success -> {
                val localPlaylists = mediaDao.observePlaylists().first()
                    .map { it.toModel() }
                    .filter { it.name.contains(query, ignoreCase = true) }
                RemoteResult.Success(result.value.copy(playlists = localPlaylists))
            }
            is RemoteResult.Failure -> result
        }
    }

    suspend fun genres(): RemoteResult<List<String>> {
        return remote()?.genres() ?: RemoteResult.Failure(ClearTuneError.Authentication())
    }

    suspend fun musicFolders(): RemoteResult<List<MusicFolder>> =
        remote()?.musicFolders() ?: RemoteResult.Failure(ClearTuneError.Authentication())

    suspend fun musicDirectory(id: String): RemoteResult<MusicDirectory> =
        remote()?.musicDirectory(id) ?: RemoteResult.Failure(ClearTuneError.Authentication())

    suspend fun lyrics(song: Song): RemoteResult<Lyrics> {
        return remote()?.lyrics(song) ?: RemoteResult.Failure(ClearTuneError.Authentication())
    }

    suspend fun coverArtUrl(id: String, size: Int = 512): String? {
        return remote()?.coverArtUrl(id, size)
    }

    suspend fun setSongFavorite(song: Song, favorite: Boolean) =
        setFavorite(FavoriteTargetType.SONG, song.id, favorite)

    suspend fun setAlbumFavorite(album: Album, favorite: Boolean) =
        setFavorite(FavoriteTargetType.ALBUM, album.id, favorite)

    suspend fun setArtistFavorite(artist: Artist, favorite: Boolean) =
        setFavorite(FavoriteTargetType.ARTIST, artist.id, favorite)

    private suspend fun setFavorite(type: FavoriteTargetType, id: String, favorite: Boolean) {
        val now = System.currentTimeMillis()
        val starredAt = now.takeIf { favorite }
        when (type) {
            FavoriteTargetType.SONG -> mediaDao.updateSongStarred(id, starredAt, now)
            FavoriteTargetType.ALBUM -> mediaDao.updateAlbumStarred(id, starredAt, now)
            FavoriteTargetType.ARTIST -> mediaDao.updateArtistStarred(id, starredAt, now)
        }
        val result = remote()?.setFavorite(type, id, favorite)
        if (result !is RemoteResult.Success) {
            database.activityDao().upsertMutation(
                PendingMutationEntity(
                    id = UUID.randomUUID().toString(),
                    type = if (favorite) "STAR_${type.name}" else "UNSTAR_${type.name}",
                    targetId = id,
                    payload = null,
                    createdAt = now,
                ),
            )
        }
    }

    private suspend fun syncPendingFavorites(remote: LibraryRemoteDataSource) {
        database.activityDao().pendingMutations().forEach { mutation ->
            val favorite = when {
                mutation.type.startsWith("STAR_") -> true
                mutation.type.startsWith("UNSTAR_") -> false
                else -> return@forEach
            }
            val typeName = mutation.type.substringAfter('_')
            val type = runCatching { FavoriteTargetType.valueOf(typeName) }.getOrNull() ?: return@forEach
            val now = System.currentTimeMillis()
            val starredAt = now.takeIf { favorite }
            when (type) {
                FavoriteTargetType.SONG -> mediaDao.updateSongStarred(mutation.targetId, starredAt, now)
                FavoriteTargetType.ALBUM -> mediaDao.updateAlbumStarred(mutation.targetId, starredAt, now)
                FavoriteTargetType.ARTIST -> mediaDao.updateArtistStarred(mutation.targetId, starredAt, now)
            }
            if (remote.setFavorite(type, mutation.targetId, favorite) is RemoteResult.Success) {
                database.activityDao().deleteMutation(mutation.id)
            }
        }
    }

    private suspend fun refreshPlaylists(remote: LibraryRemoteDataSource): ClearTuneError? {
        return when (val result = remote.playlists()) {
            is RemoteResult.Success -> {
                mediaDao.upsertPlaylists(result.value.map { it.toEntity() })
                null
            }
            is RemoteResult.Failure -> result.error
        }
    }

    private suspend fun remote(): LibraryRemoteDataSource? {
        val credentials = credentialsStore.credentials.first() ?: return null
        return runCatching {
            LibraryRemoteDataSource(apiFactory.authorized(credentials))
        }.getOrNull()
    }

    private suspend fun <T> loadAllPages(
        request: suspend (offset: Int) -> RemoteResult<List<T>>,
    ): RemoteResult<List<T>> {
        val items = mutableListOf<T>()
        var offset = 0
        while (true) {
            when (val result = request(offset)) {
                is RemoteResult.Failure -> return result
                is RemoteResult.Success -> {
                    items += result.value
                    if (result.value.size < PAGE_SIZE) return RemoteResult.Success(items)
                    offset += result.value.size
                }
            }
        }
    }

    private companion object {
        const val PAGE_SIZE = 500
    }
}
