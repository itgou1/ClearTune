package com.cleartune.app.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleartune.app.withResolvedArtwork
import com.cleartune.app.withResolvedArtistArtwork
import com.cleartune.core.model.Album
import com.cleartune.core.model.Artist
import com.cleartune.core.model.Playlist
import com.cleartune.core.model.Song
import com.cleartune.core.model.RecommendationEngine
import com.cleartune.core.model.RecommendationShelf
import com.cleartune.core.model.Lyrics
import com.cleartune.core.model.MusicFolder
import com.cleartune.core.datastore.AppPreferences
import com.cleartune.core.network.RemoteResult
import com.cleartune.core.network.SearchResults
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LibraryUiState(
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val songs: List<Song> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val isInitializing: Boolean = true,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val syncStage: LibrarySyncStage = LibrarySyncStage.IDLE,
    val lastSyncedAt: Long? = null,
)

enum class LibrarySyncStage {
    IDLE,
    LIBRARY,
    GENRES,
    FOLDERS,
}

private data class LibrarySnapshot(
    val albums: List<Album>,
    val artists: List<Artist>,
    val songs: List<Song>,
    val playlists: List<Playlist>,
)

data class SearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val isLoadingMore: Boolean = false,
    val canLoadMore: Boolean = false,
    val results: SearchResults = SearchResults(emptyList(), emptyList(), emptyList()),
    val errorMessage: String? = null,
    val loadingMoreCategory: SearchCategory? = null,
    val hasMoreArtists: Boolean = false,
    val hasMoreAlbums: Boolean = false,
    val hasMoreSongs: Boolean = false,
    val visibleArtistCount: Int = INITIAL_VISIBLE_ARTISTS,
    val visibleAlbumCount: Int = INITIAL_VISIBLE_ALBUMS,
    val visibleSongCount: Int = INITIAL_VISIBLE_SONGS,
    val visiblePlaylistCount: Int = INITIAL_VISIBLE_PLAYLISTS,
    val suggestedQueries: List<String> = emptyList(),
)

enum class SearchCategory {
    ARTISTS,
    ALBUMS,
    SONGS,
    PLAYLISTS,
}

private data class SearchRequest(
    val query: String,
    val forceRemote: Boolean,
)

private data class SearchPagingState(
    val query: String = "",
    val artistOffset: Int = 0,
    val albumOffset: Int = 0,
    val songOffset: Int = 0,
    val hasMoreArtists: Boolean = true,
    val hasMoreAlbums: Boolean = true,
    val hasMoreSongs: Boolean = true,
) {
    val canLoadMore: Boolean
        get() = hasMoreArtists || hasMoreAlbums || hasMoreSongs
}

data class DetailUiState(
    val isLoading: Boolean = false,
    val album: Album? = null,
    val artist: Artist? = null,
    val playlist: Playlist? = null,
    val songs: List<Song> = emptyList(),
    val errorMessage: String? = null,
)

data class LyricsUiState(
    val loading: Boolean = false,
    val lyrics: Lyrics? = null,
    val message: String? = null,
)

data class FolderUiState(
    val roots: List<MusicFolder> = emptyList(),
    val path: List<MusicFolder> = emptyList(),
    val folders: List<MusicFolder> = emptyList(),
    val songs: List<Song> = emptyList(),
    val loading: Boolean = false,
    val errorMessage: String? = null,
    val physicalBrowseUnsupported: Boolean = false,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class MusicViewModel @Inject constructor(
    private val repository: MusicRepository,
    private val appPreferences: AppPreferences,
) : ViewModel() {
    private val refreshing = MutableStateFlow(false)
    private val libraryError = MutableStateFlow<String?>(null)
    private val syncStage = MutableStateFlow(LibrarySyncStage.IDLE)
    private val recommendationSeed = MutableStateFlow(System.currentTimeMillis())
    private val recommendationExclusions = MutableStateFlow<Set<String>>(emptySet())
    private val recommendationEngine = RecommendationEngine()

    private val librarySnapshot = combine(
        repository.albums,
        repository.artists,
        repository.songs,
        repository.playlists,
    ) { albums, artists, songs, playlists ->
        LibrarySnapshot(albums, artists, songs, playlists)
    }

    val libraryState: StateFlow<LibraryUiState> = combine(
        librarySnapshot,
        refreshing,
        libraryError,
        syncStage,
        appPreferences.settings.map { it.lastLibrarySyncEpochMs },
    ) { snapshot, isRefreshing, errorMessage, currentSyncStage, lastSync ->
        withContext(Dispatchers.Default) {
            val resolvedAlbums = snapshot.albums.withResolvedArtwork(snapshot.songs)
            LibraryUiState(
                albums = resolvedAlbums,
                artists = snapshot.artists.withResolvedArtistArtwork(resolvedAlbums),
                songs = snapshot.songs,
                playlists = snapshot.playlists,
                isInitializing = false,
                isRefreshing = isRefreshing,
                errorMessage = errorMessage,
                syncStage = currentSyncStage,
                lastSyncedAt = lastSync.takeIf { it > 0L },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    private val searchQuery = MutableStateFlow("")
    val searchInput: StateFlow<String> = searchQuery.asStateFlow()
    private val explicitSearchRequests = MutableSharedFlow<SearchRequest>(extraBufferCapacity = 1)
    private val _searchState = MutableStateFlow(SearchUiState())
    private var searchPaging = SearchPagingState()
    val searchState: StateFlow<SearchUiState> = _searchState.asStateFlow()
    val recentSearches: StateFlow<List<String>> = appPreferences.settings
        .map { it.recentSearches }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _detailState = MutableStateFlow(DetailUiState())
    val detailState: StateFlow<DetailUiState> = _detailState.asStateFlow()

    private val _genres = MutableStateFlow<List<String>>(emptyList())
    val genres: StateFlow<List<String>> = _genres.asStateFlow()
    private val _folderState = MutableStateFlow(FolderUiState())
    val folderState: StateFlow<FolderUiState> = _folderState.asStateFlow()
    private var detailJob: Job? = null
    private val _lyricsState = MutableStateFlow(LyricsUiState())
    val lyricsState: StateFlow<LyricsUiState> = _lyricsState.asStateFlow()
    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()
    private val _detailInvalidations = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val detailInvalidations = _detailInvalidations.asSharedFlow()

    val recommendations: StateFlow<List<RecommendationShelf>> = combine(
        repository.songs,
        recommendationSeed,
        recommendationExclusions,
    ) { songs, seed, excluded ->
        recommendationEngine.generate(songs, seed, excluded)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        refresh()
        viewModelScope.launch {
            merge(
                searchQuery.debounce(SEARCH_INPUT_DEBOUNCE_MS)
                    .distinctUntilChanged()
                    .map { SearchRequest(it, false) },
                explicitSearchRequests,
            ).collectLatest { request ->
                performSearch(request.query, request.forceRemote)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            refreshing.value = true
            libraryError.value = null
            try {
                syncStage.value = LibrarySyncStage.LIBRARY
                val refreshError = repository.refreshLibrary()
                libraryError.value = refreshError?.userMessage
                if (refreshError == null) {
                    appPreferences.setLastLibrarySyncEpochMs(System.currentTimeMillis())
                }

                syncStage.value = LibrarySyncStage.GENRES
                when (val result = repository.genres()) {
                    is RemoteResult.Success -> _genres.value = normalizeGenreLabels(
                        result.value + libraryState.value.songs.map(Song::genre),
                    )
                    is RemoteResult.Failure -> Unit
                }

                syncStage.value = LibrarySyncStage.FOLDERS
                _folderState.update {
                    it.copy(loading = true, errorMessage = null, physicalBrowseUnsupported = false)
                }
                when (val result = repository.musicFolders()) {
                    is RemoteResult.Success -> _folderState.update {
                        it.copy(
                            roots = result.value,
                            loading = false,
                            errorMessage = null,
                            physicalBrowseUnsupported = false,
                        )
                    }
                    is RemoteResult.Failure -> _folderState.update {
                        it.copy(loading = false, errorMessage = result.error.userMessage)
                    }
                }
            } finally {
                syncStage.value = LibrarySyncStage.IDLE
                refreshing.value = false
            }
        }
    }

    fun updateSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun openFolder(folder: MusicFolder) {
        val oldPath = _folderState.value.path
        val existingIndex = oldPath.indexOfFirst { it.id == folder.id }
        val path = if (existingIndex >= 0) oldPath.take(existingIndex + 1) else oldPath + folder
        loadDirectory(folder, path)
    }

    fun folderBack() {
        val path = _folderState.value.path
        if (path.size <= 1) {
            _folderState.value = _folderState.value.copy(
                path = emptyList(),
                folders = emptyList(),
                songs = emptyList(),
                errorMessage = null,
                physicalBrowseUnsupported = false,
            )
        } else {
            val previous = path[path.lastIndex - 1]
            loadDirectory(previous, path.dropLast(1))
        }
    }

    private fun loadDirectory(folder: MusicFolder, path: List<MusicFolder>) {
        _folderState.value = _folderState.value.copy(
            path = path,
            folders = emptyList(),
            songs = emptyList(),
            loading = true,
            errorMessage = null,
            physicalBrowseUnsupported = false,
        )
        viewModelScope.launch {
            val isMusicFolderRoot = path.size == 1 && _folderState.value.roots.any { it.id == folder.id }
            _folderState.value = when (val result = repository.musicDirectory(folder.id)) {
                is RemoteResult.Success -> _folderState.value.copy(
                    path = path,
                    folders = result.value.folders,
                    songs = result.value.songs,
                    loading = false,
                    physicalBrowseUnsupported = isMusicFolderRoot &&
                        result.value.folders.isEmpty() && result.value.songs.isEmpty(),
                )
                is RemoteResult.Failure -> _folderState.value.copy(
                    path = path,
                    loading = false,
                    errorMessage = result.error.userMessage.takeUnless { isMusicFolderRoot },
                    physicalBrowseUnsupported = isMusicFolderRoot,
                )
            }
        }
    }

    fun submitSearch() {
        val query = searchQuery.value.trim()
        if (query.isNotBlank()) {
            viewModelScope.launch { appPreferences.addRecentSearch(query) }
            explicitSearchRequests.tryEmit(SearchRequest(query, forceRemote = true))
        }
    }

    fun loadMoreSearch(category: SearchCategory) {
        val paging = searchPaging
        val current = _searchState.value
        if (current.loadingMoreCategory != null || paging.query.isBlank()) return
        val targetVisibleCount = current.visibleCount(category) + category.displayPageSize()
        _searchState.update { it.withVisibleCount(category, targetVisibleCount) }
        if (current.resultCount(category) >= targetVisibleCount || !current.hasRemoteMore(category)) return
        _searchState.update {
            it.copy(
                isLoadingMore = true,
                loadingMoreCategory = category,
                errorMessage = null,
            )
        }
        viewModelScope.launch {
            val result = repository.search(
                query = paging.query,
                artistOffset = paging.artistOffset,
                albumOffset = paging.albumOffset,
                songOffset = paging.songOffset,
                artistCount = SEARCH_ARTIST_PAGE_SIZE.takeIf { category == SearchCategory.ARTISTS } ?: 0,
                albumCount = SEARCH_ALBUM_PAGE_SIZE.takeIf { category == SearchCategory.ALBUMS } ?: 0,
                songCount = SEARCH_SONG_PAGE_SIZE.takeIf { category == SearchCategory.SONGS } ?: 0,
            )
            if (searchPaging.query != paging.query || _searchState.value.query.trim() != paging.query) return@launch
            when (result) {
                is RemoteResult.Success -> {
                    searchPaging = paging.next(result.value, setOf(category))
                    val merged = mergeFreshSearchResults(_searchState.value.results, result.value)
                    val knownSongs = libraryState.value.songs + merged.songs
                    val resolvedResults = withContext(Dispatchers.Default) {
                        merged.withResolvedSearchArtwork(knownSongs)
                    }
                    _searchState.update {
                        it.copy(
                            isLoadingMore = false,
                            canLoadMore = searchPaging.canLoadMore,
                            loadingMoreCategory = null,
                            hasMoreArtists = searchPaging.hasMoreArtists,
                            hasMoreAlbums = searchPaging.hasMoreAlbums,
                            hasMoreSongs = searchPaging.hasMoreSongs,
                            results = resolvedResults,
                        )
                    }
                }
                is RemoteResult.Failure -> _searchState.update {
                    it.copy(
                        isLoadingMore = false,
                        loadingMoreCategory = null,
                        errorMessage = "无法加载更多：${result.error.userMessage}",
                    )
                }
            }
        }
    }

    fun removeRecentSearch(query: String) {
        viewModelScope.launch { appPreferences.removeRecentSearch(query) }
    }

    fun clearRecentSearches() {
        viewModelScope.launch { appPreferences.clearRecentSearches() }
    }

    fun refreshRecommendations() {
        recommendationSeed.value = System.currentTimeMillis()
    }

    fun updateRecommendationExclusions(ids: Set<String>) {
        recommendationExclusions.value = ids
    }

    fun toggleSongFavorite(song: Song) {
        viewModelScope.launch { repository.setSongFavorite(song, song.starredAt == null) }
    }

    fun setSongFavorite(song: Song, favorite: Boolean) {
        viewModelScope.launch { repository.setSongFavorite(song, favorite) }
    }

    fun toggleAlbumFavorite(album: Album) {
        viewModelScope.launch { repository.setAlbumFavorite(album, album.starredAt == null) }
    }

    fun toggleArtistFavorite(artist: Artist) {
        viewModelScope.launch { repository.setArtistFavorite(artist, artist.starredAt == null) }
    }

    fun loadLyrics(song: Song) {
        if (_lyricsState.value.lyrics?.songId == song.id) return
        _lyricsState.value = LyricsUiState(loading = true)
        viewModelScope.launch {
            _lyricsState.value = when (val result = repository.lyrics(song)) {
                is RemoteResult.Success -> LyricsUiState(
                    lyrics = result.value,
                    message = if (result.value.lines.isEmpty()) "暂无歌词" else null,
                )
                is RemoteResult.Failure -> LyricsUiState(message = "暂时无法获取歌词")
            }
        }
    }

    fun createPlaylist(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            _actionMessage.value = repository.createPlaylist(name)?.userMessage ?: "歌单已创建"
        }
    }

    fun renamePlaylist(id: String, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            reportPlaylistAction(id, repository.renamePlaylist(id, name), "歌单已改名")
        }
    }

    fun addPlaylistSong(id: String, songId: String) {
        viewModelScope.launch {
            reportPlaylistAction(id, repository.addPlaylistSong(id, songId), "已添加到歌单")
        }
    }

    fun removePlaylistSongs(id: String, indexes: List<Int>) {
        if (indexes.isEmpty()) return
        viewModelScope.launch {
            reportPlaylistAction(
                id,
                repository.removePlaylistSongs(id, indexes),
                "已从歌单移除 ${indexes.distinct().size} 首歌曲",
            )
        }
    }

    fun deletePlaylist(id: String, onDeleted: () -> Unit = {}) {
        viewModelScope.launch {
            val error = repository.deletePlaylist(id)
            _actionMessage.value = error?.userMessage ?: "歌单已删除"
            if (error == null) onDeleted()
        }
    }

    fun consumeActionMessage() {
        _actionMessage.value = null
    }

    fun loadAlbum(id: String) {
        _detailState.value = DetailUiState(isLoading = true)
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            launch {
                val error = repository.loadAlbum(id)
                val missing = error.isNotFound()
                _detailState.update {
                    it.copy(isLoading = false, errorMessage = error?.userMessage.takeUnless { missing })
                }
                if (missing) invalidateDetail("album/$id")
            }
            combine(repository.album(id), repository.albumSongs(id)) { album, songs -> album to songs }
                .collect { (album, songs) ->
                    _detailState.update { current -> DetailUiState(
                        isLoading = album == null && current.errorMessage == null,
                        album = album?.withResolvedArtwork(songs),
                        songs = songs,
                        errorMessage = current.errorMessage,
                    ) }
                }
        }
    }

    fun loadArtist(id: String) {
        _detailState.value = DetailUiState(isLoading = true)
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            launch {
                val error = repository.loadArtist(id)
                val missing = error.isNotFound()
                _detailState.update {
                    it.copy(isLoading = false, errorMessage = error?.userMessage.takeUnless { missing })
                }
                if (missing) invalidateDetail("artist/$id")
            }
            combine(repository.artist(id), repository.artistSongs(id)) { artist, songs -> artist to songs }
                .collect { (artist, songs) ->
                    _detailState.update { current -> DetailUiState(
                        isLoading = artist == null && current.errorMessage == null,
                        artist = artist?.withResolvedArtistArtwork(songs),
                        songs = songs,
                        errorMessage = current.errorMessage,
                    ) }
                }
        }
    }

    fun loadPlaylist(id: String) {
        _detailState.value = DetailUiState(isLoading = true)
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            launch {
                val error = repository.loadPlaylist(id)
                val missing = error.isNotFound()
                _detailState.update {
                    it.copy(isLoading = false, errorMessage = error?.userMessage.takeUnless { missing })
                }
                if (missing) invalidateDetail("playlist/$id")
            }
            combine(repository.playlist(id), repository.playlistSongs(id)) { playlist, songs -> playlist to songs }
                .collect { (playlist, songs) ->
                    _detailState.update { current -> DetailUiState(
                        isLoading = playlist == null && current.errorMessage == null,
                        playlist = playlist,
                        songs = songs,
                        errorMessage = current.errorMessage,
                    ) }
                }
        }
    }

    suspend fun coverArtUrl(id: String, size: Int = 512): String? = repository.coverArtUrl(id, size)

    private suspend fun reportPlaylistAction(id: String, error: com.cleartune.core.model.ClearTuneError?, success: String) {
        if (error.isNotFound()) {
            invalidateDetail("playlist/$id")
        } else {
            _actionMessage.value = error?.userMessage ?: success
        }
    }

    private suspend fun invalidateDetail(route: String) {
        _actionMessage.value = "内容已从服务器移除，本地缓存已自动清理"
        _detailInvalidations.emit(route)
        refresh()
    }

    private suspend fun performSearch(query: String, forceRemote: Boolean) {
        if (query.isBlank()) {
            searchPaging = SearchPagingState()
            _searchState.value = SearchUiState(
                suggestedQueries = repository.smartSearchSuggestions(),
            )
            return
        }
        val normalizedQuery = query.trim()
        val localResults = repository.localSearch(normalizedQuery)
        searchPaging = SearchPagingState(query = normalizedQuery)
        val lastSync = appPreferences.settings.first().lastLibrarySyncEpochMs
        val searchServer = shouldSearchServer(
            localResultCount = localResults.totalCount(),
            lastLibrarySyncEpochMs = lastSync,
            forced = forceRemote,
        )
        val resolvedLocalResults = withContext(Dispatchers.Default) {
            localResults.withResolvedSearchArtwork(libraryState.value.songs)
        }
        _searchState.value = SearchUiState(
            query = query,
            isSearching = searchServer,
            results = resolvedLocalResults,
            suggestedQueries = if (!searchServer && localResults.isEmpty()) {
                repository.searchCorrections(normalizedQuery)
            } else {
                emptyList()
            },
        )
        if (!searchServer) return
        _searchState.value = when (val result = repository.search(normalizedQuery)) {
            is RemoteResult.Success -> {
                searchPaging = searchPaging.next(result.value, REMOTE_SEARCH_CATEGORIES)
                val merged = mergeFreshSearchResults(localResults, result.value)
                val knownSongs = libraryState.value.songs + merged.songs
                val resolvedResults = withContext(Dispatchers.Default) {
                    merged.withResolvedSearchArtwork(knownSongs)
                }
                SearchUiState(
                    query = query,
                    canLoadMore = searchPaging.canLoadMore,
                    hasMoreArtists = searchPaging.hasMoreArtists,
                    hasMoreAlbums = searchPaging.hasMoreAlbums,
                    hasMoreSongs = searchPaging.hasMoreSongs,
                    results = resolvedResults,
                    suggestedQueries = if (merged.isEmpty()) {
                        repository.searchCorrections(normalizedQuery)
                    } else {
                        emptyList()
                    },
                )
            }
            is RemoteResult.Failure -> SearchUiState(
                query = query,
                results = localResults,
                suggestedQueries = if (localResults.isEmpty()) {
                    repository.searchCorrections(normalizedQuery)
                } else {
                    emptyList()
                },
                errorMessage = if (localResults.isEmpty()) {
                    result.error.userMessage
                } else {
                    "已显示本地结果，服务器搜索暂不可用"
                },
            )
        }
    }

    private fun SearchPagingState.next(
        page: SearchResults,
        categories: Set<SearchCategory>,
    ): SearchPagingState = copy(
        artistOffset = artistOffset + page.artists.size.takeIf { SearchCategory.ARTISTS in categories }.orZero(),
        albumOffset = albumOffset + page.albums.size.takeIf { SearchCategory.ALBUMS in categories }.orZero(),
        songOffset = songOffset + page.songs.size.takeIf { SearchCategory.SONGS in categories }.orZero(),
        hasMoreArtists = if (SearchCategory.ARTISTS in categories) {
            hasMoreArtists && page.artists.size >= SEARCH_ARTIST_PAGE_SIZE
        } else {
            hasMoreArtists
        },
        hasMoreAlbums = if (SearchCategory.ALBUMS in categories) {
            hasMoreAlbums && page.albums.size >= SEARCH_ALBUM_PAGE_SIZE
        } else {
            hasMoreAlbums
        },
        hasMoreSongs = if (SearchCategory.SONGS in categories) {
            hasMoreSongs && page.songs.size >= SEARCH_SONG_PAGE_SIZE
        } else {
            hasMoreSongs
        },
    )

    private companion object {
        const val SEARCH_INPUT_DEBOUNCE_MS = 250L
        const val SEARCH_ARTIST_PAGE_SIZE = 30
        const val SEARCH_ALBUM_PAGE_SIZE = 30
        const val SEARCH_SONG_PAGE_SIZE = 50
    }
}

private fun SearchResults.withResolvedSearchArtwork(knownSongs: List<Song>): SearchResults {
    val resolvedAlbums = albums.withResolvedArtwork(knownSongs)
    return copy(
        albums = resolvedAlbums,
        artists = artists.withResolvedArtistArtwork(resolvedAlbums),
    )
}

private fun SearchUiState.visibleCount(category: SearchCategory): Int = when (category) {
    SearchCategory.ARTISTS -> visibleArtistCount
    SearchCategory.ALBUMS -> visibleAlbumCount
    SearchCategory.SONGS -> visibleSongCount
    SearchCategory.PLAYLISTS -> visiblePlaylistCount
}

private fun SearchUiState.resultCount(category: SearchCategory): Int = when (category) {
    SearchCategory.ARTISTS -> results.artists.size
    SearchCategory.ALBUMS -> results.albums.size
    SearchCategory.SONGS -> results.songs.size
    SearchCategory.PLAYLISTS -> results.playlists.size
}

private fun SearchUiState.hasRemoteMore(category: SearchCategory): Boolean = when (category) {
    SearchCategory.ARTISTS -> hasMoreArtists
    SearchCategory.ALBUMS -> hasMoreAlbums
    SearchCategory.SONGS -> hasMoreSongs
    SearchCategory.PLAYLISTS -> false
}

private fun SearchUiState.withVisibleCount(category: SearchCategory, count: Int): SearchUiState = when (category) {
    SearchCategory.ARTISTS -> copy(visibleArtistCount = count)
    SearchCategory.ALBUMS -> copy(visibleAlbumCount = count)
    SearchCategory.SONGS -> copy(visibleSongCount = count)
    SearchCategory.PLAYLISTS -> copy(visiblePlaylistCount = count)
}

private fun SearchCategory.displayPageSize(): Int = when (this) {
    SearchCategory.ARTISTS -> INITIAL_VISIBLE_ARTISTS
    SearchCategory.ALBUMS -> INITIAL_VISIBLE_ALBUMS
    SearchCategory.SONGS -> INITIAL_VISIBLE_SONGS
    SearchCategory.PLAYLISTS -> INITIAL_VISIBLE_PLAYLISTS
}

private fun Int?.orZero(): Int = this ?: 0

private fun SearchResults.isEmpty(): Boolean =
    artists.isEmpty() && albums.isEmpty() && songs.isEmpty() && playlists.isEmpty()

private fun SearchResults.totalCount(): Int = artists.size + albums.size + songs.size + playlists.size

private val REMOTE_SEARCH_CATEGORIES = setOf(
    SearchCategory.ARTISTS,
    SearchCategory.ALBUMS,
    SearchCategory.SONGS,
)

private const val INITIAL_VISIBLE_ARTISTS = 6
private const val INITIAL_VISIBLE_ALBUMS = 6
private const val INITIAL_VISIBLE_SONGS = 20
private const val INITIAL_VISIBLE_PLAYLISTS = 6
