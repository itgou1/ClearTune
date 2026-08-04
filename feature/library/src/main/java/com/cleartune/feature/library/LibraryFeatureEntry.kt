package com.cleartune.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.cleartune.core.contracts.LibraryRepository
import com.cleartune.core.contracts.PlaybackGateway
import com.cleartune.core.contracts.PlaylistRepository
import com.cleartune.core.model.LibraryHome

data class LibraryFeatureDependencies(
    val libraryRepository: LibraryRepository,
    val playbackGateway: PlaybackGateway,
    val playlistRepository: PlaylistRepository,
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
        when (route) {
            LibraryRoutes.root -> Content(dependencies, onNavigate, uiInputs)
            LibraryRoutes.songs -> SongsScreen(dependencies, onBack, uiInputs.onTrackMore)
            LibraryRoutes.albums -> AlbumsScreen(dependencies, onBack)
            LibraryRoutes.artists -> ArtistsScreen(dependencies, onBack)
            LibraryRoutes.folders -> FoldersScreen(
                dependencies = dependencies,
                folders = uiInputs.folders,
                selectedFolder = uiInputs.selectedFolder,
                folderTracks = uiInputs.folderTracks,
                onBack = onBack,
                onOpenFolder = uiInputs.onOpenFolder,
                onTrackMore = uiInputs.onTrackMore,
            )
            LibraryRoutes.search -> SearchScreen(dependencies, onBack, uiInputs.onTrackMore)
            else -> Content(dependencies, onNavigate, uiInputs)
        }
    }
}
