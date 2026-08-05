package com.cleartune.app

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cleartune.core.designsystem.theme.ClearTuneTheme
import com.cleartune.core.model.DownloadCommand
import com.cleartune.core.model.DownloadState
import com.cleartune.core.model.PlaybackCommand
import com.cleartune.core.model.PlaylistId
import com.cleartune.core.model.QueueItemId
import com.cleartune.core.model.ThemeMode
import com.cleartune.core.contracts.QueueRepository
import com.cleartune.feature.downloads.DownloadsFeatureDependencies
import com.cleartune.feature.downloads.DownloadsFeatureEntry
import com.cleartune.feature.library.LibraryFeatureDependencies
import com.cleartune.feature.library.LibraryFeatureEntry
import com.cleartune.feature.library.LibraryFeatureUiInputs
import com.cleartune.feature.library.LibraryRoutes
import com.cleartune.feature.library.LibrarySyncUiState
import com.cleartune.feature.library.LocalAccessUiState
import com.cleartune.feature.player.MiniPlayer
import com.cleartune.feature.player.PlayerFeatureDependencies
import com.cleartune.feature.player.PlayerFeatureEntry
import com.cleartune.feature.player.PlayerTrackActionState
import com.cleartune.feature.playlists.PlaylistsFeatureDependencies
import com.cleartune.feature.playlists.PlaylistsFeatureEntry
import com.cleartune.feature.settings.SettingsFeatureDependencies
import com.cleartune.feature.settings.SettingsFeatureEntry
import com.cleartune.feature.settings.isReducedMotionEnabled
import com.cleartune.feature.sources.SourceRoute
import com.cleartune.feature.sources.SourcesFeatureDependencies
import com.cleartune.feature.sources.LocalMediaAccess
import com.cleartune.feature.sources.LocalSourceDetailState
import com.cleartune.feature.sources.SourcesFeatureEntry
import com.cleartune.playback.PlaybackQueueStateWriter
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

object AppRoutes {
    const val Library = "library"
    const val LibrarySongs = "library/songs"
    const val LibraryAlbums = "library/albums"
    const val LibraryAlbumDetail = "library/albums/{albumId}"
    const val LibraryArtists = "library/artists"
    const val LibraryArtistDetail = "library/artists/{artistId}"
    const val LibraryFolders = "library/folders"
    const val LibraryFolderDetail = "library/folders/{folderPath}"
    const val LibrarySearch = "library/search"
    const val Player = "player"
    const val Playlists = "playlists"
    const val PlaylistDetail = "playlists/{playlistId}"
    const val Sources = "sources"
    const val SourceAdd = "sources/add-webdav"
    const val SourceDetail = "sources/{sourceId}"
    const val SourceEdit = "sources/{sourceId}/edit"
    const val SourceBrowseRoot = "sources/{sourceId}/browse"
    const val SourceBrowse = "sources/{sourceId}/browse/{relativePath}"
    const val Downloads = "downloads"
    const val Settings = "settings"

    val all = listOf(
        Library, LibrarySongs, LibraryAlbums, LibraryAlbumDetail, LibraryArtists, LibraryArtistDetail,
        LibraryFolders, LibraryFolderDetail, LibrarySearch, Player, Playlists, PlaylistDetail,
        Sources, SourceAdd, SourceDetail, SourceEdit, SourceBrowseRoot, SourceBrowse, Downloads, Settings,
    )
    val restorable = all

    fun albumDetail(albumId: String) = "library/albums/${encode(albumId)}"
    fun albumId(route: String): String? = dynamicId(route, "library/albums/")
    fun artistDetail(artistId: String) = "library/artists/${encode(artistId)}"
    fun artistId(route: String): String? = dynamicId(route, "library/artists/")
    fun folder(path: String) = "library/folders/${encode(path)}"
    fun folderPath(route: String): String? = dynamicId(route, "library/folders/")
    fun playlistDetail(playlistId: String) = "$Playlists/${encode(playlistId)}"
    fun playlistId(route: String): String? = dynamicId(route, "$Playlists/")
    fun sourceDetail(sourceId: String) = SourceRoute.Root(com.cleartune.core.model.SourceId(sourceId)).encoded()
    fun sourceEdit(sourceId: String) = SourceRoute.Edit(com.cleartune.core.model.SourceId(sourceId)).encoded()
    fun sourceBrowse(sourceId: String, relativePath: String) =
        SourceRoute.Browse(com.cleartune.core.model.SourceId(sourceId), relativePath).encoded()
    fun sourceBrowseArgs(route: String): Pair<String, String>? =
        (SourceRoute.parse(route) as? SourceRoute.Browse)?.let { it.sourceId.value to it.relativePath }

    fun restore(route: String?): String = when {
        route == null -> Library
        route in setOf(
            Library, LibrarySongs, LibraryAlbums, LibraryArtists, LibraryFolders, LibrarySearch,
            Player, Playlists, Sources, SourceAdd, Downloads, Settings,
        ) -> route
        albumId(route) != null || artistId(route) != null || folderPath(route) != null || playlistId(route) != null -> route
        SourceRoute.parse(route) !is SourceRoute.Invalid -> route
        else -> Library
    }

    private fun dynamicId(route: String, prefix: String): String? = route.removePrefix(prefix)
        .takeIf { route.startsWith(prefix) && it.isNotBlank() && '/' !in it }
        ?.let(::decode)

    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
    private fun decode(value: String) = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrNull()
}

internal suspend fun playQueueOccurrence(
    queueRepository: QueueRepository,
    stateWriter: PlaybackQueueStateWriter,
    occurrenceId: QueueItemId,
    onQueueChanged: suspend () -> Unit,
) {
    val snapshot = queueRepository.observeQueue().first()
    val selectedIndex = snapshot.items.indexOfFirst { it.id == occurrenceId }
    require(selectedIndex >= 0) { "Queue occurrence not found" }
    stateWriter.updatePlaybackState(
        currentIndex = selectedIndex,
        positionMs = if (selectedIndex == snapshot.currentIndex) null else 0,
        playWhenReady = false,
    )
    onQueueChanged()
}

@Composable
fun ClearTuneApp(container: AppContainer, startDestination: String = AppRoutes.Library) {
    val settings by container.settingsRepository.settings.collectAsState(initial = com.cleartune.core.model.AppSettings())
    val darkTheme = when (settings.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    val reducedMotion = isReducedMotionEnabled(settings.reducedMotionMode, ValueAnimator.areAnimatorsEnabled())
    val context = LocalContext.current
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE
    var localAccess by remember(permission) {
        mutableStateOf(
            if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) LocalAccessUiState.GRANTED
            else LocalAccessUiState.NOT_REQUESTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        localAccess = if (granted) LocalAccessUiState.GRANTED else LocalAccessUiState.DENIED_CAN_ASK
        if (granted) container.enqueueLocalScan()
    }
    val scan by container.localScanState.collectAsState()
    val sync = when (scan.phase) {
        com.cleartune.data.local.LocalScanPhase.READING,
        com.cleartune.data.local.LocalScanPhase.APPLYING,
        -> LibrarySyncUiState.Running(scan.processed, scan.total)
        com.cleartune.data.local.LocalScanPhase.FAILED -> LibrarySyncUiState.Failed(scan.errorMessage ?: "Scan failed")
        else -> LibrarySyncUiState.Idle
    }
    val libraryDependencies = remember(container) {
        LibraryFeatureDependencies(
            container.libraryRepository,
            container.libraryBrowsePort,
            container.playbackGateway,
            container.playlistRepository,
        )
    }
    val sourceDependencies = remember(container, localAccess, scan) {
        SourcesFeatureDependencies(
            sourceRepository = container.sourceRepository,
            controller = container.sourceController,
            syncStatus = container.sourceSyncStatus,
            localDetail = LocalSourceDetailState(
                access = when (localAccess) {
                    LocalAccessUiState.GRANTED -> LocalMediaAccess.GRANTED
                    LocalAccessUiState.DENIED_PERMANENTLY -> LocalMediaAccess.PERMANENTLY_DENIED
                    else -> LocalMediaAccess.NEEDS_PERMISSION
                },
                scanning = scan.phase == com.cleartune.data.local.LocalScanPhase.READING ||
                    scan.phase == com.cleartune.data.local.LocalScanPhase.APPLYING,
                processed = scan.processed,
                total = scan.total,
                errorMessage = scan.errorMessage,
            ),
            onRequestLocalAccess = { permissionLauncher.launch(permission) },
            onScanLocal = container::enqueueLocalScan,
            onOpenAppSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
        )
    }
    val queueTitles = container.trackTitleFlow
    val playerDependencies = remember(container) {
        PlayerFeatureDependencies(
            playbackGateway = container.playbackGateway,
            queueRepository = container.queueRepository,
            onQueueChanged = container.playbackGateway::syncQueue,
            queueTitles = queueTitles,
            observeTrackActions = { trackId ->
                combine(
                    container.downloadRepository.observeDownloads(),
                    container.favoritesRepository.observeIsFavorite(trackId),
                ) { downloads, isFavorite ->
                    PlayerTrackActionState(
                        isFavorite = isFavorite,
                        isDownloaded = downloads.any { it.trackId == trackId && it.state == DownloadState.COMPLETED },
                        canFavorite = true,
                        canDownload = true,
                    )
                }
            },
            onToggleFavorite = container.favoritesRepository::toggle,
            onToggleDownload = { trackId ->
                val existing = container.downloadRepository.observeDownloads().first().firstOrNull { it.trackId == trackId }
                container.downloadRepository.dispatch(
                    if (existing == null) DownloadCommand.Enqueue(trackId) else DownloadCommand.Delete(existing.id),
                )
            },
            onPlayOccurrence = { occurrenceId ->
                playQueueOccurrence(
                    container.queueRepository,
                    container.queueRepository,
                    occurrenceId,
                    container.playbackGateway::syncQueue,
                )
                container.playbackGateway.dispatch(PlaybackCommand.Play)
            },
            onRetry = { trackId -> container.playbackGateway.dispatch(PlaybackCommand.PlayTrack(trackId)) },
            dynamicBackground = container.settingsProductController.productSettings.map { it.dynamicBackground },
        )
    }
    val playlistDependencies = remember(container) {
        PlaylistsFeatureDependencies(
            container.playlistRepository,
            container.playbackGateway,
            container.queueRepository,
            container.playlistDetailsProvider,
            queueTitles,
            container.playbackGateway::syncQueue,
        )
    }

    ClearTuneTheme(darkTheme = darkTheme) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
            val navController = rememberNavController()
            Column(Modifier.fillMaxSize()) {
                NavHost(
                    navController = navController,
                    startDestination = AppRoutes.restore(startDestination),
                    modifier = Modifier.weight(1f),
                    enterTransition = { if (reducedMotion) EnterTransition.None else fadeIn() },
                    exitTransition = { if (reducedMotion) ExitTransition.None else fadeOut() },
                    popEnterTransition = { if (reducedMotion) EnterTransition.None else fadeIn() },
                    popExitTransition = { if (reducedMotion) ExitTransition.None else fadeOut() },
                ) {
                    fun libraryInputs() = LibraryFeatureUiInputs(
                        localAccess = localAccess,
                        sync = sync,
                        onRequestLocalAccess = { permissionLauncher.launch(permission) },
                        onRefreshLocalLibrary = container::enqueueLocalScan,
                        onOpenSystemSettings = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                        onAddWebDav = { navController.navigate(AppRoutes.SourceAdd) },
                        onOpenFolder = { navController.navigate(AppRoutes.folder(it)) },
                        onOpenAlbum = { navController.navigate(AppRoutes.albumDetail(it.id.value)) },
                        onOpenArtist = { navController.navigate(AppRoutes.artistDetail(it.id.value)) },
                    )
                    fun libraryRoute(pattern: String, featureRoute: String) = composable(pattern) {
                        LibraryFeatureEntry.Screen(
                            featureRoute,
                            libraryDependencies,
                            navController::navigateOrBack,
                            navController::popBackStack,
                            libraryInputs(),
                        )
                    }
                    libraryRoute(AppRoutes.Library, LibraryRoutes.root)
                    libraryRoute(AppRoutes.LibrarySongs, LibraryRoutes.songs)
                    libraryRoute(AppRoutes.LibraryAlbums, LibraryRoutes.albums)
                    libraryRoute(AppRoutes.LibraryArtists, LibraryRoutes.artists)
                    libraryRoute(AppRoutes.LibraryFolders, LibraryRoutes.folders)
                    libraryRoute(AppRoutes.LibrarySearch, LibraryRoutes.search)
                    composable(AppRoutes.LibraryAlbumDetail, listOf(navArgument("albumId") { type = NavType.StringType })) { entry ->
                        val id = entry.arguments?.getString("albumId")
                        val albums by container.libraryBrowsePort.observeAlbums().collectAsState(initial = emptyList())
                        LibraryFeatureEntry.Screen(
                            LibraryRoutes.albumDetail, libraryDependencies, navController::navigateOrBack,
                            navController::popBackStack, libraryInputs().copy(selectedAlbum = albums.firstOrNull { it.id.value == id }),
                        )
                    }
                    composable(AppRoutes.LibraryArtistDetail, listOf(navArgument("artistId") { type = NavType.StringType })) { entry ->
                        val id = entry.arguments?.getString("artistId")
                        val artists by container.libraryBrowsePort.observeArtists().collectAsState(initial = emptyList())
                        LibraryFeatureEntry.Screen(
                            LibraryRoutes.artistDetail, libraryDependencies, navController::navigateOrBack,
                            navController::popBackStack, libraryInputs().copy(selectedArtist = artists.firstOrNull { it.id.value == id }),
                        )
                    }
                    composable(AppRoutes.LibraryFolderDetail, listOf(navArgument("folderPath") { type = NavType.StringType })) { entry ->
                        LibraryFeatureEntry.Screen(
                            LibraryRoutes.folders, libraryDependencies, navController::navigateOrBack,
                            navController::popBackStack,
                            libraryInputs().copy(selectedFolder = entry.arguments?.getString("folderPath")),
                        )
                    }
                    composable(AppRoutes.Player) { PlayerFeatureEntry.Content(playerDependencies, navController::navigateOrBack) }
                    composable(AppRoutes.Playlists) {
                        PlaylistsFeatureEntry.Content(playlistDependencies, navController::navigateOrBack)
                    }
                    composable(AppRoutes.PlaylistDetail, listOf(navArgument("playlistId") { type = NavType.StringType })) { entry ->
                        val id = entry.arguments?.getString("playlistId")?.let(::PlaylistId)
                        PlaylistsFeatureEntry.Content(playlistDependencies, navController::navigateOrBack, id)
                    }
                    composable(AppRoutes.Sources) {
                        SourcesFeatureEntry.Content(sourceDependencies, navController::navigateOrBack, AppRoutes.Sources)
                    }
                    composable(AppRoutes.SourceAdd) {
                        SourcesFeatureEntry.Content(sourceDependencies, navController::navigateOrBack, AppRoutes.SourceAdd)
                    }
                    composable(AppRoutes.SourceDetail, listOf(navArgument("sourceId") { type = NavType.StringType })) { entry ->
                        val route = entry.arguments?.getString("sourceId")?.let(AppRoutes::sourceDetail) ?: AppRoutes.Sources
                        SourcesFeatureEntry.Content(sourceDependencies, navController::navigateOrBack, route)
                    }
                    composable(AppRoutes.SourceEdit, listOf(navArgument("sourceId") { type = NavType.StringType })) { entry ->
                        val route = entry.arguments?.getString("sourceId")?.let(AppRoutes::sourceEdit) ?: AppRoutes.Sources
                        SourcesFeatureEntry.Content(sourceDependencies, navController::navigateOrBack, route)
                    }
                    composable(AppRoutes.SourceBrowseRoot, listOf(navArgument("sourceId") { type = NavType.StringType })) { entry ->
                        val sourceId = entry.arguments?.getString("sourceId")
                        val route = sourceId?.let { AppRoutes.sourceBrowse(it, "") } ?: AppRoutes.Sources
                        SourcesFeatureEntry.Content(sourceDependencies, navController::navigateOrBack, route)
                    }
                    composable(
                        AppRoutes.SourceBrowse,
                        listOf(
                            navArgument("sourceId") { type = NavType.StringType },
                            navArgument("relativePath") { type = NavType.StringType },
                        ),
                    ) { entry ->
                        val sourceId = entry.arguments?.getString("sourceId")
                        val path = entry.arguments?.getString("relativePath")
                        val route = if (sourceId != null && path != null) AppRoutes.sourceBrowse(sourceId, path) else AppRoutes.Sources
                        SourcesFeatureEntry.Content(sourceDependencies, navController::navigateOrBack, route)
                    }
                    composable(AppRoutes.Downloads) {
                        DownloadsFeatureEntry.Content(
                            DownloadsFeatureDependencies(
                                container.downloadRepository,
                                container.playbackGateway,
                                container.downloadTitleResolver,
                            ),
                            navController::navigateOrBack,
                        )
                    }
                    composable(AppRoutes.Settings) {
                        SettingsFeatureEntry.Content(
                            SettingsFeatureDependencies(
                                container.settingsRepository,
                                container.sourceRepository,
                                container.downloadRepository,
                                container.settingsProductController.productSettings,
                                container.settingsProductController::dispatch,
                            ),
                            navController::navigateOrBack,
                        )
                    }
                }
                MiniPlayer(
                    dependencies = playerDependencies,
                    onOpenPlayer = { navController.navigate(AppRoutes.Player) },
                )
            }
        }
    }
}

private fun NavHostController.navigateOrBack(route: String) {
    if (route == "back") popBackStack() else navigate(route)
}
