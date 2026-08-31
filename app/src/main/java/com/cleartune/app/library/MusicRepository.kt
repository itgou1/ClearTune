package com.cleartune.app.library

import com.cleartune.core.database.ClearTuneDatabase
import com.cleartune.core.database.PlaylistSongEntity
import com.cleartune.core.database.PendingMutationEntity
import com.cleartune.core.database.toCacheWrite
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
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Singleton
class MusicRepository @Inject constructor(
    private val credentialsStore: CredentialsStore,
    private val database: ClearTuneDatabase,
    private val apiFactory: OpenSubsonicApiFactory,
) {
    private val mediaDao = database.mediaDao()
    private val lyricsDao = database.lyricsDao()
    private val coverArtUrls = ConcurrentHashMap<String, String>()
    private val remoteSearchCache = ConcurrentHashMap<RemoteSearchCacheKey, CachedRemoteSearch>()
    @Volatile
    private var searchIndexReady = false

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

        val albumsResult = albumsRequest.await()
        val artistsResult = artistsRequest.await()
        val songsResult = songsRequest.await()
        if (
            albumsResult is RemoteResult.Success &&
            artistsResult is RemoteResult.Success &&
            songsResult is RemoteResult.Success
        ) {
            mediaDao.replaceLibrary(
                albums = albumsResult.value.map { it.toEntity() },
                artists = artistsResult.value.map { it.toEntity() },
                songs = songsResult.value.map { it.toEntity() },
            )
        } else {
            (albumsResult as? RemoteResult.Failure)?.let { errors += it.error }
            (artistsResult as? RemoteResult.Failure)?.let { errors += it.error }
            (songsResult as? RemoteResult.Failure)?.let { errors += it.error }
        }
        when (val result = playlistsRequest.await()) {
            is RemoteResult.Success -> mediaDao.replacePlaylists(result.value.map { it.toEntity() })
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
        rebuildSearchIndex()
        remoteSearchCache.clear()
        errors.firstOrNull()
    }

    suspend fun loadAlbum(id: String): ClearTuneError? {
        val remote = remote() ?: return ClearTuneError.Authentication()
        return when (val result = remote.album(id)) {
            is RemoteResult.Success -> {
                mediaDao.upsertAlbums(listOf(result.value.album.toEntity()))
                mediaDao.upsertSongs(result.value.songs.map { it.toEntity() })
                mediaDao.upsertSearchDocuments(
                    listOf(searchDocument(result.value.album)) + result.value.songs.map(::searchDocument),
                )
                null
            }
            is RemoteResult.Failure -> {
                if (result.error.isNotFound()) mediaDao.deleteAlbum(id)
                result.error
            }
        }
    }

    suspend fun loadArtist(id: String): ClearTuneError? {
        val remote = remote() ?: return ClearTuneError.Authentication()
        return when (val result = remote.artist(id)) {
            is RemoteResult.Success -> {
                mediaDao.upsertArtists(listOf(result.value.artist.toEntity()))
                mediaDao.upsertAlbums(result.value.albums.map { it.toEntity() })
                mediaDao.upsertSearchDocuments(
                    listOf(searchDocument(result.value.artist)) + result.value.albums.map(::searchDocument),
                )
                when (val songs = remote.topSongs(result.value.artist.name)) {
                    is RemoteResult.Success -> {
                        mediaDao.upsertSongs(songs.value.map { it.toEntity() })
                        mediaDao.upsertSearchDocuments(songs.value.map(::searchDocument))
                    }
                    is RemoteResult.Failure -> Unit
                }
                null
            }
            is RemoteResult.Failure -> {
                if (result.error.isNotFound()) mediaDao.deleteArtist(id)
                result.error
            }
        }
    }

    suspend fun loadPlaylist(id: String): ClearTuneError? {
        val remote = remote() ?: return ClearTuneError.Authentication()
        return when (val result = remote.playlist(id)) {
            is RemoteResult.Success -> {
                mediaDao.upsertPlaylists(listOf(result.value.playlist.toEntity()))
                mediaDao.upsertSongs(result.value.songs.map { it.toEntity() })
                mediaDao.upsertSearchDocuments(
                    listOf(searchDocument(result.value.playlist)) + result.value.songs.map(::searchDocument),
                )
                mediaDao.replacePlaylistSongs(
                    id,
                    result.value.songs.mapIndexed { index, song ->
                        PlaylistSongEntity(id, song.id, index)
                    },
                )
                null
            }
            is RemoteResult.Failure -> {
                if (result.error.isNotFound()) {
                    mediaDao.clearPlaylistSongs(id)
                    mediaDao.deletePlaylist(id)
                }
                result.error
            }
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
            is RemoteResult.Failure -> cleanMissingPlaylist(id, result.error)
        }
    }

    suspend fun addPlaylistSong(id: String, songId: String): ClearTuneError? {
        val remote = remote() ?: return ClearTuneError.Authentication()
        return when (val result = remote.addPlaylistSongs(id, listOf(songId))) {
            is RemoteResult.Success -> loadPlaylist(id)
            is RemoteResult.Failure -> cleanMissingPlaylist(id, result.error)
        }
    }

    suspend fun removePlaylistSongs(id: String, indexes: List<Int>): ClearTuneError? {
        if (indexes.isEmpty()) return null
        val remote = remote() ?: return ClearTuneError.Authentication()
        return when (val result = remote.removePlaylistSongs(id, indexes.distinct().sortedDescending())) {
            is RemoteResult.Success -> loadPlaylist(id)
            is RemoteResult.Failure -> cleanMissingPlaylist(id, result.error)
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
            is RemoteResult.Failure -> {
                if (result.error.isNotFound()) {
                    removeLocalPlaylist(id)
                    null
                } else {
                    result.error
                }
            }
        }
    }

    fun album(id: String): Flow<Album?> = mediaDao.observeAlbum(id).map { it?.toModel() }
    fun artist(id: String): Flow<Artist?> = mediaDao.observeArtist(id).map { it?.toModel() }
    fun playlist(id: String): Flow<Playlist?> = mediaDao.observePlaylist(id).map { it?.toModel() }
    fun albumSongs(id: String): Flow<List<Song>> = mediaDao.observeSongsForAlbum(id).map { list -> list.map { it.toModel() } }
    fun artistSongs(id: String): Flow<List<Song>> = mediaDao.observeSongsForArtist(id)
        .map { list -> list.map { it.toModel() } }
    fun playlistSongs(id: String): Flow<List<Song>> = mediaDao.observePlaylistSongs(id).map { list -> list.map { it.toModel() } }

    suspend fun localSearch(query: String): SearchResults = withContext(Dispatchers.Default) {
        val plan = buildSearchQueryPlan(query)
        if (plan.matchQuery.isBlank()) {
            return@withContext SearchResults(emptyList(), emptyList(), emptyList())
        }
        ensureSearchIndex()
        coroutineScope {
            val artistDocumentsRequest = async {
                rankedDocuments(SearchDocumentType.ARTIST, plan, LOCAL_ARTIST_RESULT_LIMIT)
            }
            val albumDocumentsRequest = async {
                rankedDocuments(SearchDocumentType.ALBUM, plan, LOCAL_ALBUM_RESULT_LIMIT)
            }
            val songDocumentsRequest = async {
                rankedDocuments(SearchDocumentType.SONG, plan, LOCAL_SONG_RESULT_LIMIT)
            }
            val playlistDocumentsRequest = async {
                rankedDocuments(SearchDocumentType.PLAYLIST, plan, LOCAL_PLAYLIST_RESULT_LIMIT)
            }
            val artistDocuments = artistDocumentsRequest.await()
            val albumDocuments = albumDocumentsRequest.await()
            val songDocuments = songDocumentsRequest.await()
            val playlistDocuments = playlistDocumentsRequest.await()
            val artistsRequest = async {
                mediaDao.artistsByIds(artistDocuments.map { it.entityId }).associateBy { it.id }
            }
            val albumsRequest = async {
                mediaDao.albumsByIds(albumDocuments.map { it.entityId }).associateBy { it.id }
            }
            val songsRequest = async {
                mediaDao.songsByIds(songDocuments.map { it.entityId }).associateBy { it.id }
            }
            val playlistsRequest = async {
                mediaDao.playlistsByIds(playlistDocuments.map { it.entityId }).associateBy { it.id }
            }
            val artistsById = artistsRequest.await()
            val albumsById = albumsRequest.await()
            val songsById = songsRequest.await()
            val playlistsById = playlistsRequest.await()
            SearchResults(
                artists = artistDocuments.mapNotNull { artistsById[it.entityId]?.toModel() },
                albums = albumDocuments.mapNotNull { albumsById[it.entityId]?.toModel() },
                songs = songDocuments.mapNotNull { songsById[it.entityId]?.toModel() },
                playlists = playlistDocuments.mapNotNull { playlistsById[it.entityId]?.toModel() },
            )
        }
    }

    suspend fun searchCorrections(query: String): List<String> = withContext(Dispatchers.Default) {
        ensureSearchIndex()
        closestSearchSuggestions(
            query = query,
            documents = mediaDao.searchSuggestionDocuments(SEARCH_SUGGESTION_CANDIDATE_LIMIT),
        )
    }

    suspend fun smartSearchSuggestions(): List<String> = withContext(Dispatchers.Default) {
        val songs = mediaDao.observeSongs().first()
            .sortedWith(compareByDescending<com.cleartune.core.database.SongEntity> { it.playCount }
                .thenByDescending { it.lastPlayedAt ?: 0L })
        buildList {
            normalizeGenreLabels(songs.map { it.genre })
                .take(4)
                .forEach(::add)
            songs.map { it.artistName.trim() }
                .filter(String::isNotEmpty)
                .distinctBy { it.lowercase() }
                .take(4)
                .forEach(::add)
        }.distinct().take(8)
    }

    suspend fun search(
        query: String,
        artistOffset: Int = 0,
        albumOffset: Int = 0,
        songOffset: Int = 0,
        artistCount: Int = SEARCH_ARTIST_PAGE_SIZE,
        albumCount: Int = SEARCH_ALBUM_PAGE_SIZE,
        songCount: Int = SEARCH_SONG_PAGE_SIZE,
    ): RemoteResult<SearchResults> {
        val remote = remote() ?: return RemoteResult.Failure(ClearTuneError.Authentication())
        val cacheKey = RemoteSearchCacheKey(
            query = normalizeSearchText(query),
            artistOffset = artistOffset,
            albumOffset = albumOffset,
            songOffset = songOffset,
            artistCount = artistCount,
            albumCount = albumCount,
            songCount = songCount,
        )
        val now = System.currentTimeMillis()
        val cached = remoteSearchCache[cacheKey]?.takeIf {
            now - it.cachedAt < REMOTE_SEARCH_CACHE_TTL_MS
        }?.results
        val result = cached?.let { RemoteResult.Success(it) } ?: remote.search(
            query = query,
            artistCount = artistCount,
            artistOffset = artistOffset,
            albumCount = albumCount,
            albumOffset = albumOffset,
            songCount = songCount,
            songOffset = songOffset,
        ).also { remoteResult ->
            if (remoteResult is RemoteResult.Success) {
                remoteSearchCache[cacheKey] = CachedRemoteSearch(now, remoteResult.value)
                if (remoteSearchCache.size > MAX_REMOTE_SEARCH_CACHE_ENTRIES) remoteSearchCache.clear()
            }
        }
        if (result is RemoteResult.Success) {
            val fresh = result.value.withPreservedLocalState()
            mediaDao.upsertArtists(fresh.artists.map { it.toEntity() })
            mediaDao.upsertAlbums(fresh.albums.map { it.toEntity() })
            mediaDao.upsertSongs(fresh.songs.map { it.toEntity() })
            val freshSearchDocuments = withContext(Dispatchers.Default) {
                fresh.artists.map(::searchDocument) +
                    fresh.albums.map(::searchDocument) +
                    fresh.songs.map(::searchDocument)
            }
            mediaDao.upsertSearchDocuments(freshSearchDocuments)
            val localPlaylists = mediaDao.observePlaylists().first()
                .map { it.toModel() }
                .filter { it.name.contains(query, ignoreCase = true) }
            return RemoteResult.Success(fresh.copy(playlists = localPlaylists))
        }
        return result
    }

    suspend fun genres(): RemoteResult<List<String>> {
        return remote()?.genres() ?: RemoteResult.Failure(ClearTuneError.Authentication())
    }

    suspend fun musicFolders(): RemoteResult<List<MusicFolder>> =
        remote()?.musicFolders() ?: RemoteResult.Failure(ClearTuneError.Authentication())

    suspend fun musicDirectory(id: String): RemoteResult<MusicDirectory> =
        remote()?.musicDirectory(id) ?: RemoteResult.Failure(ClearTuneError.Authentication())

    suspend fun lyrics(song: Song): RemoteResult<Lyrics> {
        val credentials = credentialsStore.credentials.first()
            ?: return RemoteResult.Failure(ClearTuneError.Authentication())
        val serverUrl = credentials.baseUrl.trimEnd('/')
        runCatching {
            lyricsDao.lyrics(serverUrl, credentials.username, song.id)
        }.getOrNull()?.let { cached ->
            return RemoteResult.Success(cached.toModel())
        }

        val remote = runCatching {
            LibraryRemoteDataSource(apiFactory.authorized(credentials))
        }.getOrNull() ?: return RemoteResult.Failure(ClearTuneError.Authentication())
        return when (val result = remote.lyrics(song)) {
            is RemoteResult.Success -> {
                val write = result.value.toCacheWrite(serverUrl, credentials.username)
                runCatching { lyricsDao.replace(write.cache, write.lines) }
                result
            }
            is RemoteResult.Failure -> result
        }
    }

    suspend fun coverArtUrl(id: String, size: Int = 512): String? {
        val credentials = credentialsStore.credentials.first() ?: return null
        val safeSize = size.coerceIn(96, 1_200)
        val key = "${credentials.baseUrl}|${credentials.username}|$id|$safeSize"
        coverArtUrls[key]?.let { return it }
        val url = runCatching {
            LibraryRemoteDataSource(apiFactory.authorized(credentials)).coverArtUrl(id, safeSize)
        }.getOrNull() ?: return null
        if (coverArtUrls.size >= MAX_COVER_URL_CACHE_ENTRIES) coverArtUrls.clear()
        coverArtUrls[key] = url
        return url
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
            val result = remote.setFavorite(type, mutation.targetId, favorite)
            val targetMissing = result is RemoteResult.Failure && result.error.isNotFound()
            if (result is RemoteResult.Success || targetMissing) {
                database.activityDao().deleteMutation(mutation.id)
            }
        }
    }

    private suspend fun refreshPlaylists(remote: LibraryRemoteDataSource): ClearTuneError? {
        return when (val result = remote.playlists()) {
            is RemoteResult.Success -> {
                mediaDao.replacePlaylists(result.value.map { it.toEntity() })
                rebuildSearchIndex()
                null
            }
            is RemoteResult.Failure -> result.error
        }
    }

    private suspend fun cleanMissingPlaylist(id: String, error: ClearTuneError): ClearTuneError {
        if (error.isNotFound()) removeLocalPlaylist(id)
        return error
    }

    private suspend fun removeLocalPlaylist(id: String) {
        mediaDao.clearPlaylistSongs(id)
        mediaDao.deletePlaylist(id)
    }

    private suspend fun remote(): LibraryRemoteDataSource? {
        val credentials = credentialsStore.credentials.first() ?: return null
        return runCatching {
            LibraryRemoteDataSource(apiFactory.authorized(credentials))
        }.getOrNull()
    }

    private suspend fun rankedDocuments(
        type: String,
        plan: SearchQueryPlan,
        resultLimit: Int,
    ) = rankSearchDocuments(
        documents = mediaDao.searchDocuments(type, plan.matchQuery, LOCAL_SEARCH_CANDIDATE_LIMIT),
        plan = plan,
    ).take(resultLimit)

    private suspend fun ensureSearchIndex() {
        if (searchIndexReady) return
        val documentCount = mediaDao.searchDocumentCount()
        val currentFormatCount = mediaDao.searchDocumentFormatCount(SEARCH_INDEX_FORMAT_MARKER)
        if (documentCount == 0 || currentFormatCount != documentCount) {
            rebuildSearchIndex()
        } else {
            searchIndexReady = true
        }
    }

    private suspend fun rebuildSearchIndex() = coroutineScope {
        val artistsRequest = async { mediaDao.observeArtists().first().map { it.toModel() } }
        val albumsRequest = async { mediaDao.observeAlbums().first().map { it.toModel() } }
        val songsRequest = async { mediaDao.observeSongs().first().map { it.toModel() } }
        val playlistsRequest = async { mediaDao.observePlaylists().first().map { it.toModel() } }
        val artists = artistsRequest.await()
        val albums = albumsRequest.await()
        val songs = songsRequest.await()
        val playlists = playlistsRequest.await()
        val documents = withContext(Dispatchers.Default) {
            artists.map(::searchDocument) +
                albums.map(::searchDocument) +
                songs.map(::searchDocument) +
                playlists.map(::searchDocument)
        }
        mediaDao.replaceSearchDocuments(documents)
        searchIndexReady = true
    }

    private suspend fun SearchResults.withPreservedLocalState(): SearchResults {
        val localArtists = mediaDao.artistsByIds(artists.map(Artist::id)).associateBy { it.id }
        val localAlbums = mediaDao.albumsByIds(albums.map(Album::id)).associateBy { it.id }
        val localSongs = mediaDao.songsByIds(songs.map(Song::id)).associateBy { it.id }
        return copy(
            artists = artists.map { remote ->
                localArtists[remote.id]?.let { local -> remote.copy(starredAt = local.starredAt) } ?: remote
            },
            albums = albums.map { remote ->
                localAlbums[remote.id]?.let { local -> remote.copy(starredAt = local.starredAt) } ?: remote
            },
            songs = songs.map { remote ->
                localSongs[remote.id]?.let { local ->
                    remote.copy(
                        playCount = maxOf(local.playCount, remote.playCount),
                        lastPlayedAt = listOfNotNull(local.lastPlayedAt, remote.lastPlayedAt).maxOrNull(),
                        starredAt = local.starredAt,
                    )
                } ?: remote
            },
        )
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
        const val SEARCH_ARTIST_PAGE_SIZE = 30
        const val SEARCH_ALBUM_PAGE_SIZE = 30
        const val SEARCH_SONG_PAGE_SIZE = 50
        const val MAX_COVER_URL_CACHE_ENTRIES = 2_048
        const val LOCAL_SEARCH_CANDIDATE_LIMIT = 500
        const val LOCAL_ARTIST_RESULT_LIMIT = 80
        const val LOCAL_ALBUM_RESULT_LIMIT = 120
        const val LOCAL_SONG_RESULT_LIMIT = 200
        const val LOCAL_PLAYLIST_RESULT_LIMIT = 50
        const val SEARCH_SUGGESTION_CANDIDATE_LIMIT = 1_500
        const val MAX_REMOTE_SEARCH_CACHE_ENTRIES = 64
        const val REMOTE_SEARCH_CACHE_TTL_MS = 5 * 60 * 1_000L
    }

    private data class RemoteSearchCacheKey(
        val query: String,
        val artistOffset: Int,
        val albumOffset: Int,
        val songOffset: Int,
        val artistCount: Int,
        val albumCount: Int,
        val songCount: Int,
    )

    private data class CachedRemoteSearch(
        val cachedAt: Long,
        val results: SearchResults,
    )
}

internal fun ClearTuneError?.isNotFound(): Boolean =
    this is ClearTuneError.Server && code in setOf(70, 404)
