package com.cleartune.feature.library

import com.cleartune.core.model.LibraryHome
import com.cleartune.core.model.Album
import com.cleartune.core.model.Artist
import com.cleartune.core.model.TrackSummary

object LibraryRoutes {
    const val root = "library"
    const val songs = "library/songs"
    const val albums = "library/albums"
    const val artists = "library/artists"
    const val albumDetail = "library/albums/detail"
    const val artistDetail = "library/artists/detail"
    const val folders = "library/folders"
    const val search = "library/search"
    const val playlists = "playlists"
    const val downloads = "downloads"
    const val settings = "settings"
}

enum class LocalAccessUiState { NOT_REQUESTED, GRANTED, DENIED_CAN_ASK, DENIED_PERMANENTLY, UNAVAILABLE }

sealed interface LibrarySyncUiState {
    data object Idle : LibrarySyncUiState
    data class Running(val processed: Int = 0, val total: Int = 0) : LibrarySyncUiState
    data class Failed(val message: String) : LibrarySyncUiState
}

enum class LibraryEmptyReason { READY_TO_SCAN, PERMISSION_REQUIRED, LOCAL_UNAVAILABLE, NO_RESULTS }

data class LibraryCategoryUi(
    val id: String,
    val title: String,
    val route: String,
    val count: Int? = null,
)

data class LibraryHomeUiState(
    val categories: List<LibraryCategoryUi>,
    val recentAdded: List<TrackSummary>,
    val recentPlayed: List<TrackSummary>,
    val emptyReason: LibraryEmptyReason?,
    val isSyncing: Boolean,
    val syncProgress: Float?,
    val inlineMessage: String?,
    val showScanAction: Boolean,
    val showOpenSettingsAction: Boolean,
    val showAddWebDavAction: Boolean,
)

object LibraryUiStateFactory {
    fun create(
        home: LibraryHome,
        localAccess: LocalAccessUiState = LocalAccessUiState.NOT_REQUESTED,
        sync: LibrarySyncUiState = LibrarySyncUiState.Idle,
    ): LibraryHomeUiState {
        val isEmpty = home.songCount == 0
        val emptyReason = when {
            !isEmpty -> null
            localAccess == LocalAccessUiState.DENIED_CAN_ASK ||
                localAccess == LocalAccessUiState.DENIED_PERMANENTLY -> LibraryEmptyReason.PERMISSION_REQUIRED
            localAccess == LocalAccessUiState.UNAVAILABLE -> LibraryEmptyReason.LOCAL_UNAVAILABLE
            else -> LibraryEmptyReason.READY_TO_SCAN
        }
        return LibraryHomeUiState(
            categories = listOf(
                LibraryCategoryUi("songs", "歌曲", LibraryRoutes.songs, home.songCount),
                LibraryCategoryUi("albums", "专辑", LibraryRoutes.albums, home.albumCount),
                LibraryCategoryUi("artists", "歌手", LibraryRoutes.artists, home.artistCount),
                LibraryCategoryUi("playlists", "歌单", LibraryRoutes.playlists),
                LibraryCategoryUi("folders", "文件夹", LibraryRoutes.folders),
                LibraryCategoryUi("downloads", "已下载", LibraryRoutes.downloads),
            ),
            recentAdded = home.recentAdded.take(4),
            recentPlayed = home.recentPlayed,
            emptyReason = emptyReason,
            isSyncing = sync is LibrarySyncUiState.Running,
            syncProgress = (sync as? LibrarySyncUiState.Running)?.let { running ->
                if (running.total > 0) running.processed.toFloat() / running.total else null
            },
            inlineMessage = (sync as? LibrarySyncUiState.Failed)?.message,
            showScanAction = isEmpty && localAccess in setOf(
                LocalAccessUiState.NOT_REQUESTED,
                LocalAccessUiState.GRANTED,
                LocalAccessUiState.DENIED_CAN_ASK,
            ),
            showOpenSettingsAction = isEmpty && localAccess == LocalAccessUiState.DENIED_PERMANENTLY,
            showAddWebDavAction = isEmpty,
        )
    }
}

data class LibraryFolderUi(
    val path: String,
    val trackCount: Int,
    val sourceName: String = "本地音乐",
)

data class LibraryFeatureUiInputs(
    val localAccess: LocalAccessUiState = LocalAccessUiState.NOT_REQUESTED,
    val sync: LibrarySyncUiState = LibrarySyncUiState.Idle,
    val selectedAlbum: Album? = null,
    val selectedArtist: Artist? = null,
    val selectedFolder: String? = null,
    val onRequestLocalAccess: () -> Unit = {},
    val onRefreshLocalLibrary: () -> Unit = {},
    val onOpenSystemSettings: () -> Unit = {},
    val onAddWebDav: () -> Unit = {},
    val onOpenFolder: (String) -> Unit = {},
    val onOpenAlbum: (Album) -> Unit = {},
    val onOpenArtist: (Artist) -> Unit = {},
    val onTrackMore: ((TrackSummary) -> Unit)? = null,
)
