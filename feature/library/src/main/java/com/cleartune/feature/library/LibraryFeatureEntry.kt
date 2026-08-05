package com.cleartune.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.cleartune.core.contracts.LibraryRepository
import com.cleartune.core.contracts.PlaybackGateway
import com.cleartune.core.contracts.PlaylistRepository
import com.cleartune.core.contracts.QueueRepository
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cleartune.core.model.LibraryHome

data class LibraryFeatureDependencies(
    val libraryRepository: LibraryRepository,
    val libraryBrowsePort: LibraryBrowsePort,
    val playbackGateway: PlaybackGateway,
    val playlistRepository: PlaylistRepository,
    val queueRepository: QueueRepository? = null,
    val onQueueChanged: suspend () -> Unit = {},
)

object LibraryFeatureEntry {
    const val route = LibraryRoutes.root

    @Composable
    fun Content(
        dependencies: LibraryFeatureDependencies,
        onNavigate: (String) -> Unit,
        uiInputs: LibraryFeatureUiInputs = LibraryFeatureUiInputs(),
    ) {
        val home by dependencies.libraryRepository.observeLibraryHome()
            .collectAsState(initial = LibraryHome())
        LibraryHomeScreen(
            state = LibraryUiStateFactory.create(home, uiInputs.localAccess, uiInputs.sync),
            onNavigate = onNavigate,
            onRequestLocalAccess = uiInputs.onRequestLocalAccess,
            onOpenSystemSettings = uiInputs.onOpenSystemSettings,
            onAddWebDav = uiInputs.onAddWebDav,
            onRefresh = uiInputs.onRefreshLocalLibrary,
        )
    }

    @Composable
    fun Screen(
        route: String,
        dependencies: LibraryFeatureDependencies,
        onNavigate: (String) -> Unit,
        onBack: () -> Unit,
        uiInputs: LibraryFeatureUiInputs = LibraryFeatureUiInputs(),
    ) {
        var selectedTrack by remember { mutableStateOf<com.cleartune.core.model.TrackSummary?>(null) }
        val onTrackMore: (com.cleartune.core.model.TrackSummary) -> Unit = { track ->
            selectedTrack = track
            uiInputs.onTrackMore?.invoke(track)
        }
        val browseState = remember(dependencies.libraryRepository, dependencies.libraryBrowsePort) {
            LibraryBrowseState(dependencies.libraryRepository, dependencies.libraryBrowsePort)
        }
        when (route) {
            LibraryRoutes.root -> Content(dependencies, onNavigate, uiInputs)
            LibraryRoutes.songs -> SongsScreen(dependencies, onBack, onTrackMore)
            LibraryRoutes.albums -> {
                val albums by browseState.albums.collectAsState(initial = emptyList())
                AlbumsScreen(albums, onBack, uiInputs.onOpenAlbum)
            }
            LibraryRoutes.artists -> {
                val artists by browseState.artists.collectAsState(initial = emptyList())
                ArtistsScreen(artists, onBack, uiInputs.onOpenArtist)
            }
            LibraryRoutes.albumDetail -> {
                val detailFlow = remember(browseState, uiInputs.selectedAlbum) {
                    browseState.albumDetail(uiInputs.selectedAlbum)
                }
                val detail by detailFlow.collectAsState(
                    initial = LibraryAlbumDetailState(uiInputs.selectedAlbum, emptyList()),
                )
                AlbumDetailScreen(dependencies, detail.album, detail.tracks, onBack, onTrackMore)
            }
            LibraryRoutes.artistDetail -> {
                val detailFlow = remember(browseState, uiInputs.selectedArtist) {
                    browseState.artistDetail(uiInputs.selectedArtist)
                }
                val detail by detailFlow.collectAsState(
                    initial = LibraryArtistDetailState(uiInputs.selectedArtist, emptyList(), emptyList()),
                )
                ArtistDetailScreen(
                    dependencies,
                    detail.artist,
                    detail.tracks,
                    detail.albums,
                    onBack,
                    uiInputs.onOpenAlbum,
                    onTrackMore,
                )
            }
            LibraryRoutes.folders -> {
                val folderFlow = remember(browseState, uiInputs.selectedFolder) {
                    browseState.folder(uiInputs.selectedFolder)
                }
                val folderState by folderFlow.collectAsState(
                    initial = LibraryFolderBrowseState(emptyList(), uiInputs.selectedFolder, emptyList()),
                )
                FoldersScreen(
                    dependencies = dependencies,
                    folders = folderState.folders,
                    selectedFolder = folderState.selectedFolder,
                    folderTracks = folderState.tracks,
                    onBack = onBack,
                    onOpenFolder = uiInputs.onOpenFolder,
                    onTrackMore = onTrackMore,
                )
            }
            LibraryRoutes.search -> SearchScreen(
                dependencies,
                onBack,
                onTrackMore,
                uiInputs.onOpenAlbum,
                uiInputs.onOpenArtist,
                uiInputs.onOpenPlaylist,
            )
            else -> Content(dependencies, onNavigate, uiInputs)
        }
        selectedTrack?.let { track ->
            LibraryTrackActionSheet(
                track = track,
                dependencies = dependencies,
                uiInputs = uiInputs,
                onDismiss = { selectedTrack = null },
            )
        }
    }
}
