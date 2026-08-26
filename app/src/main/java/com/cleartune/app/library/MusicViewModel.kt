package com.cleartune.app.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryUiState(
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val songs: List<Song> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
)

data class SearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val results: SearchResults = SearchResults(emptyList(), emptyList(), emptyList()),
    val errorMessage: String? = null,
)

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
    private val recommendationSeed = MutableStateFlow(System.currentTimeMillis())
    private val recommendationExclusions = MutableStateFlow<Set<String>>(emptySet())
    private val recommendationEngine = RecommendationEngine()

    val libraryState: StateFlow<LibraryUiState> = combine(
        repository.albums,
        repository.artists,
        repository.songs,
        repository.playlists,
        refreshing,
    ) { albums, artists, songs, playlists, isRefreshing ->
        LibraryUiState(
            albums = albums,
            artists = artists,
            songs = songs,
            playlists = playlists,
            isRefreshing = isRefreshing,
            errorMessage = libraryError.value,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    private val searchQuery = MutableStateFlow("")
    private val _searchState = MutableStateFlow(SearchUiState())
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
            searchQuery.debounce(350).distinctUntilChanged().collectLatest { query ->
                performSearch(query)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            refreshing.value = true
            _folderState.update {
                it.copy(loading = true, errorMessage = null, physicalBrowseUnsupported = false)
            }
            libraryError.value = repository.refreshLibrary()?.userMessage
            when (val result = repository.genres()) {
                is RemoteResult.Success -> _genres.value = result.value
                is RemoteResult.Failure -> Unit
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
            refreshing.value = false
        }
    }

    fun updateSearchQuery(query: String) {
        _searchState.value = _searchState.value.copy(query = query)
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
        val query = _searchState.value.query.trim()
        if (query.isNotBlank()) viewModelScope.launch { appPreferences.addRecentSearch(query) }
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
            _actionMessage.value = repository.renamePlaylist(id, name)?.userMessage ?: "歌单已改名"
        }
    }

    fun addPlaylistSong(id: String, songId: String) {
        viewModelScope.launch {
            _actionMessage.value = repository.addPlaylistSong(id, songId)?.userMessage ?: "已添加到歌单"
        }
    }

    fun removePlaylistSongs(id: String, indexes: List<Int>) {
        if (indexes.isEmpty()) return
        viewModelScope.launch {
            _actionMessage.value = repository.removePlaylistSongs(id, indexes)?.userMessage
                ?: "已从歌单移除 ${indexes.distinct().size} 首歌曲"
        }
    }

    fun deletePlaylist(id: String) {
        viewModelScope.launch {
            _actionMessage.value = repository.deletePlaylist(id)?.userMessage ?: "歌单已删除"
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
                _detailState.update { it.copy(isLoading = false, errorMessage = error?.userMessage) }
            }
            combine(repository.album(id), repository.albumSongs(id)) { album, songs -> album to songs }
                .collect { (album, songs) ->
                    _detailState.update { current -> DetailUiState(
                        isLoading = album == null && current.errorMessage == null,
                        album = album,
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
                _detailState.update { it.copy(isLoading = false, errorMessage = error?.userMessage) }
            }
            combine(repository.artist(id), repository.artistSongs(id)) { artist, songs -> artist to songs }
                .collect { (artist, songs) ->
                    _detailState.update { current -> DetailUiState(
                        isLoading = artist == null && current.errorMessage == null,
                        artist = artist,
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
                _detailState.update { it.copy(isLoading = false, errorMessage = error?.userMessage) }
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

    private suspend fun performSearch(query: String) {
        if (query.isBlank()) {
            _searchState.value = SearchUiState()
            return
        }
        _searchState.value = _searchState.value.copy(isSearching = true, errorMessage = null)
        _searchState.value = when (val result = repository.search(query.trim())) {
            is RemoteResult.Success -> SearchUiState(query = query, results = result.value)
            is RemoteResult.Failure -> SearchUiState(query = query, errorMessage = result.error.userMessage)
        }
    }
}
