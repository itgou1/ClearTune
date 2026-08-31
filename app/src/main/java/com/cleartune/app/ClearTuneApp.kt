package com.cleartune.app

import android.icu.text.AlphabeticIndex
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.FilterQuality
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.cleartune.app.library.DetailUiState
import com.cleartune.app.library.LibraryUiState
import com.cleartune.app.library.LibrarySyncStage
import com.cleartune.app.library.FolderUiState
import com.cleartune.app.library.MusicViewModel
import com.cleartune.app.library.SearchUiState
import com.cleartune.app.library.SearchCategory
import com.cleartune.app.library.genreLabelsMatch
import com.cleartune.app.library.resultCount
import com.cleartune.app.player.PlayerViewModel
import com.cleartune.app.download.DownloadViewModel
import com.cleartune.app.settings.SettingsViewModel
import com.cleartune.core.model.Album
import com.cleartune.core.model.Artist
import com.cleartune.core.model.DownloadState
import com.cleartune.core.model.Playlist
import com.cleartune.core.model.ServerProfile
import com.cleartune.core.model.Song
import com.cleartune.core.model.RecommendationShelf
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class MainDestination(
    val route: String,
    val label: Int,
    val icon: ImageVector,
)

private enum class SearchResultFilter {
    ALL,
    SONGS,
    ALBUMS,
    ARTISTS,
    PLAYLISTS,
}

private val mainDestinations = listOf(
    MainDestination("home", R.string.nav_home, Icons.Rounded.Home),
    MainDestination("search", R.string.nav_search, Icons.Rounded.Search),
    MainDestination("library", R.string.nav_library, Icons.Rounded.LibraryMusic),
    MainDestination("my", R.string.nav_my, Icons.Rounded.Person),
)

private data class LibrarySongIndexAnchor(
    val label: String,
    val songIndex: Int,
)

private data class IndexedLibrarySongs(
    val songs: List<Song>,
    val anchors: List<LibrarySongIndexAnchor>,
)

private enum class LibrarySongSort {
    TITLE,
    PLAY_COUNT,
    RECENTLY_ADDED,
}

@Composable
fun ClearTuneApp(
    profile: ServerProfile,
    restoredOffline: Boolean,
    viewModel: MusicViewModel,
    playerViewModel: PlayerViewModel,
    downloadViewModel: DownloadViewModel,
    settingsViewModel: SettingsViewModel,
    onLogout: () -> Unit,
) {
    val navController = rememberNavController()
    val libraryState by viewModel.libraryState.collectAsStateWithLifecycle()
    val searchState by viewModel.searchState.collectAsStateWithLifecycle()
    val recentSearches by viewModel.recentSearches.collectAsStateWithLifecycle()
    val detailState by viewModel.detailState.collectAsStateWithLifecycle()
    val genres by viewModel.genres.collectAsStateWithLifecycle()
    val folderState by viewModel.folderState.collectAsStateWithLifecycle()
    val recommendations by viewModel.recommendations.collectAsStateWithLifecycle()
    val lyricsState by viewModel.lyricsState.collectAsStateWithLifecycle()
    val actionMessage by viewModel.actionMessage.collectAsStateWithLifecycle()
    val playerState by playerViewModel.state.collectAsStateWithLifecycle()
    val playerMessage by playerViewModel.message.collectAsStateWithLifecycle()
    val downloads by downloadViewModel.downloads.collectAsStateWithLifecycle()
    val updateState by settingsViewModel.updateState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val openNowPlaying = {
        if (!navController.popBackStack("now-playing", inclusive = false)) {
            navController.navigate("now-playing") { launchSingleTop = true }
        }
    }
    val showNavigationBar = currentRoute in mainDestinations.map { it.route }
    val showMiniPlayer = playerState.currentSong != null && currentRoute !in setOf("now-playing", "queue")
    val viewDownloadsLabel = stringResource(R.string.view_downloads)
    val viewUpdateLabel = stringResource(R.string.update_available_action)
    val updateAvailableMessage = updateState.release
        ?.takeIf { it.newer && !updateState.ignored }
        ?.let { stringResource(R.string.update_available, it.version) }
    val offlineSessionMessage = stringResource(R.string.offline_session_restored)
    LaunchedEffect(restoredOffline) {
        if (restoredOffline) snackbarHostState.showSnackbar(offlineSessionMessage)
    }
    LaunchedEffect(playerMessage) {
        playerMessage?.let {
            snackbarHostState.showSnackbar(it)
            playerViewModel.consumeMessage()
        }
    }
    LaunchedEffect(actionMessage) {
        actionMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeActionMessage()
        }
    }
    LaunchedEffect(updateAvailableMessage) {
        updateAvailableMessage?.let { message ->
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = viewUpdateLabel,
            )
            if (result == SnackbarResult.ActionPerformed && currentRoute != "settings") {
                navController.navigate("settings")
            }
        }
    }
    LaunchedEffect(downloadViewModel) {
        downloadViewModel.messages.collect { message ->
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = viewDownloadsLabel,
            )
            if (result == SnackbarResult.ActionPerformed && currentRoute != "downloads") {
                navController.navigate("downloads")
            }
        }
    }
    LaunchedEffect(playerState.currentSong?.id) {
        viewModel.updateRecommendationExclusions(setOfNotNull(playerState.currentSong?.id))
        playerState.currentSong?.let(viewModel::loadLyrics)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            AnimatedVisibility(
                visible = showNavigationBar || showMiniPlayer,
                enter = slideInVertically(
                    initialOffsetY = { it / 3 },
                    animationSpec = ClearTuneMotion.standard(),
                ) + fadeIn(ClearTuneMotion.quick()),
                exit = slideOutVertically(
                    targetOffsetY = { it / 3 },
                    animationSpec = ClearTuneMotion.standard(),
                ) + fadeOut(ClearTuneMotion.quick()),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shadowElevation = 8.dp,
                ) {
                    Column(
                        Modifier
                            .navigationBarsPadding()
                            .animateContentSize(animationSpec = ClearTuneMotion.standard()),
                    ) {
                        AnimatedVisibility(
                            visible = showMiniPlayer,
                            enter = slideInVertically(
                                initialOffsetY = { it / 3 },
                                animationSpec = ClearTuneMotion.standard(),
                            ) + fadeIn(ClearTuneMotion.quick()),
                            exit = slideOutVertically(
                                targetOffsetY = { it / 3 },
                                animationSpec = ClearTuneMotion.standard(),
                            ) + fadeOut(ClearTuneMotion.quick()),
                        ) {
                            playerState.currentSong?.let {
                                MiniPlayer(
                                    state = playerState,
                                    musicViewModel = viewModel,
                                    onOpen = openNowPlaying,
                                    onToggle = playerViewModel::togglePlayPause,
                                )
                            }
                        }
                        AnimatedVisibility(
                            visible = showNavigationBar,
                            enter = slideInVertically(
                                initialOffsetY = { it / 3 },
                                animationSpec = ClearTuneMotion.standard(),
                            ) + fadeIn(ClearTuneMotion.quick()),
                            exit = slideOutVertically(
                                targetOffsetY = { it / 3 },
                                animationSpec = ClearTuneMotion.standard(),
                            ) + fadeOut(ClearTuneMotion.quick()),
                        ) {
                            NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                tonalElevation = 0.dp,
                                windowInsets = WindowInsets(0, 0, 0, 0),
                            ) {
                                mainDestinations.forEach { destination ->
                                    NavigationBarItem(
                                        selected = currentRoute == destination.route,
                                        onClick = {
                                            navController.navigate(destination.route) {
                                                popUpTo(navController.graph.findStartDestination().id) {
                                                    saveState = true
                                                }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        },
                                        icon = {
                                            Icon(destination.icon, contentDescription = stringResource(destination.label))
                                        },
                                        label = { Text(stringResource(destination.label)) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier
                .padding(padding)
                .then(if (showNavigationBar) Modifier.statusBarsPadding() else Modifier),
            enterTransition = {
                val switchesMainDestination =
                    initialState.destination.route in mainDestinations.map { it.route } &&
                        targetState.destination.route in mainDestinations.map { it.route }
                if (switchesMainDestination) {
                    fadeIn(ClearTuneMotion.standard()) + scaleIn(
                        initialScale = 0.985f,
                        animationSpec = ClearTuneMotion.standard(),
                    )
                } else {
                    fadeIn(ClearTuneMotion.standard()) + slideInHorizontally(
                        initialOffsetX = { it / 10 },
                        animationSpec = ClearTuneMotion.emphasized(),
                    )
                }
            },
            exitTransition = {
                val switchesMainDestination =
                    initialState.destination.route in mainDestinations.map { it.route } &&
                        targetState.destination.route in mainDestinations.map { it.route }
                if (switchesMainDestination) {
                    fadeOut(ClearTuneMotion.quick()) + scaleOut(
                        targetScale = 0.99f,
                        animationSpec = ClearTuneMotion.quick(),
                    )
                } else {
                    fadeOut(ClearTuneMotion.quick()) + slideOutHorizontally(
                        targetOffsetX = { -it / 16 },
                        animationSpec = ClearTuneMotion.standard(),
                    )
                }
            },
            popEnterTransition = {
                fadeIn(ClearTuneMotion.standard()) + slideInHorizontally(
                    initialOffsetX = { -it / 10 },
                    animationSpec = ClearTuneMotion.emphasized(),
                )
            },
            popExitTransition = {
                fadeOut(ClearTuneMotion.quick()) + slideOutHorizontally(
                    targetOffsetX = { it / 16 },
                    animationSpec = ClearTuneMotion.standard(),
                )
            },
        ) {
            composable("home") {
                HomeScreen(
                    profile = profile,
                    state = libraryState,
                    viewModel = viewModel,
                    onRefresh = viewModel::refresh,
                    onAlbum = { navController.navigate("album/${Uri.encode(it)}") },
                    onPlay = playerViewModel::play,
                    recommendations = recommendations,
                    onDiscovery = { navController.navigate("discovery") },
                    onRefreshRecommendations = viewModel::refreshRecommendations,
                )
            }
            composable("search") {
                SearchScreen(
                    state = searchState,
                    recentSearches = recentSearches,
                    genres = genres,
                    playlists = libraryState.playlists,
                    viewModel = viewModel,
                    onAlbum = { navController.navigate("album/${Uri.encode(it)}") },
                    onArtist = { navController.navigate("artist/${Uri.encode(it)}") },
                    onPlay = playerViewModel::play,
                    onPlaylist = { navController.navigate("playlist/${Uri.encode(it)}") },
                )
            }
            composable("library") {
                LibraryScreen(
                    state = libraryState,
                    genres = genres,
                    folderState = folderState,
                    viewModel = viewModel,
                    onRefresh = viewModel::refresh,
                    onAlbum = { navController.navigate("album/${Uri.encode(it)}") },
                    onArtist = { navController.navigate("artist/${Uri.encode(it)}") },
                    onPlay = playerViewModel::play,
                    onPlayNext = playerViewModel::playNext,
                    onDownload = { downloadViewModel.download(listOf(it)) },
                    onGenre = { navController.navigate("genre/${Uri.encode(it)}") },
                )
            }
            composable(
                route = "genre/{name}",
                arguments = listOf(navArgument("name") { type = NavType.StringType }),
            ) { entry ->
                val genre = Uri.decode(entry.arguments?.getString("name").orEmpty())
                GenreSongsScreen(
                    genre = genre,
                    songs = libraryState.songs.filter { genreLabelsMatch(it.genre, genre) },
                    viewModel = viewModel,
                    onBack = navController::popBackStack,
                    onPlay = playerViewModel::play,
                )
            }
            composable("my") {
                MyScreen(
                    profile = profile,
                    playlists = libraryState.playlists,
                    viewModel = viewModel,
                    likedCount = libraryState.songs.count { it.starredAt != null },
                    offlineCount = downloads.count { it.state == DownloadState.COMPLETED },
                    downloadCount = downloads.count { it.state != DownloadState.COMPLETED },
                    isConnecting = libraryState.isRefreshing,
                    connectionError = libraryState.errorMessage,
                    onFavorites = { navController.navigate("favorites") },
                    onListeningProfile = { navController.navigate("listening-profile") },
                    onPlaylist = { navController.navigate("playlist/${Uri.encode(it)}") },
                    onCreatePlaylist = viewModel::createPlaylist,
                    onOffline = { navController.navigate("offline") },
                    onDownloads = { navController.navigate("downloads") },
                    onSettings = { navController.navigate("settings") },
                )
            }
            composable("favorites") {
                FavoriteSongsScreen(
                    songs = libraryState.songs.filter { it.starredAt != null },
                    viewModel = viewModel,
                    onBack = navController::popBackStack,
                    onPlay = playerViewModel::play,
                )
            }
            composable("listening-profile") {
                ListeningProfileScreen(
                    username = profile.username,
                    songs = libraryState.songs,
                    musicViewModel = viewModel,
                    onBack = navController::popBackStack,
                    onPlay = playerViewModel::play,
                )
            }
            composable("offline") {
                OfflineMusicScreen(
                    downloads = downloads,
                    songs = libraryState.songs,
                    onBack = navController::popBackStack,
                    onPlay = playerViewModel::play,
                    onBrowseLibrary = {
                        navController.navigate("library") {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable(
                route = "album/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                LaunchedEffect(id) { viewModel.loadAlbum(id) }
                LaunchedEffect(id) {
                    viewModel.detailInvalidations.collect { route ->
                        if (route == "album/$id") navController.popBackStack()
                    }
                }
                AlbumDetailScreen(
                    state = detailState,
                    playlists = libraryState.playlists,
                    viewModel = viewModel,
                    onBack = navController::popBackStack,
                    onPlay = playerViewModel::play,
                    onDownload = { downloadViewModel.download(it) },
                )
            }
            composable(
                route = "artist/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                LaunchedEffect(id) { viewModel.loadArtist(id) }
                LaunchedEffect(id) {
                    viewModel.detailInvalidations.collect { route ->
                        if (route == "artist/$id") navController.popBackStack()
                    }
                }
                ArtistDetailScreen(
                    state = detailState,
                    albums = libraryState.albums.filter { it.artistId == id },
                    viewModel = viewModel,
                    onAlbum = { navController.navigate("album/${Uri.encode(it)}") },
                    onBack = navController::popBackStack,
                    onPlay = playerViewModel::play,
                    onDownload = { downloadViewModel.download(it) },
                )
            }
            composable(
                route = "playlist/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                LaunchedEffect(id) { viewModel.loadPlaylist(id) }
                LaunchedEffect(id) {
                    viewModel.detailInvalidations.collect { route ->
                        if (route == "playlist/$id") navController.popBackStack()
                    }
                }
                PlaylistDetailScreen(
                    state = detailState,
                    viewModel = viewModel,
                    onBack = navController::popBackStack,
                    onPlay = playerViewModel::play,
                    onDownload = { downloadViewModel.download(it) },
                    allSongs = libraryState.songs,
                    onRename = viewModel::renamePlaylist,
                    onAddSong = viewModel::addPlaylistSong,
                    onRemoveSongs = viewModel::removePlaylistSongs,
                    onDelete = viewModel::deletePlaylist,
                )
            }
            composable("settings") {
                FullSettingsScreen(
                    profile = profile,
                    viewModel = settingsViewModel,
                    onBack = navController::popBackStack,
                    onEqualizer = { navController.navigate("equalizer") },
                    onLogout = onLogout,
                )
            }
            composable("discovery") {
                DiscoveryScreen(
                    shelves = recommendations,
                    viewModel = viewModel,
                    onRefresh = viewModel::refreshRecommendations,
                    onPlay = playerViewModel::play,
                    onShelf = { navController.navigate("recommendation/${Uri.encode(it)}") },
                    onBack = navController::popBackStack,
                )
            }
            composable(
                route = "recommendation/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                RecommendationShelfScreen(
                    shelf = recommendations.firstOrNull { it.id == id },
                    viewModel = viewModel,
                    onRefresh = viewModel::refreshRecommendations,
                    onPlay = playerViewModel::play,
                    onFavorite = viewModel::toggleSongFavorite,
                    onBack = navController::popBackStack,
                )
            }
            composable("now-playing") {
                NowPlayingScreen(
                    state = playerState,
                    playerViewModel = playerViewModel,
                    musicViewModel = viewModel,
                    lyricsState = lyricsState,
                    isFavorite = playerState.currentSong?.let { current ->
                        libraryState.songs.firstOrNull { it.id == current.id }?.starredAt != null
                    } == true,
                    onBack = navController::popBackStack,
                    onQueue = { navController.navigate("queue") { launchSingleTop = true } },
                    onEqualizer = { navController.navigate("equalizer") },
                    onFavorite = { song, favorite -> viewModel.setSongFavorite(song, favorite) },
                    onDownload = { song -> downloadViewModel.download(listOf(song)) },
                )
            }
            composable("queue") {
                QueueScreen(
                    state = playerState,
                    playerViewModel = playerViewModel,
                    musicViewModel = viewModel,
                    onBack = navController::popBackStack,
                    onOpenPlayer = openNowPlaying,
                    onTogglePlayback = playerViewModel::togglePlayPause,
                    onBrowseLibrary = {
                        navController.navigate("library") {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable("equalizer") {
                EqualizerScreen(
                    viewModel = settingsViewModel,
                    onBack = navController::popBackStack,
                )
            }
            composable("downloads") {
                DownloadsScreen(
                    downloads = downloads,
                    songs = libraryState.songs,
                    viewModel = downloadViewModel,
                    onBack = navController::popBackStack,
                    onPlay = playerViewModel::play,
                    onBrowseLibrary = {
                        navController.navigate("library") {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable("lyrics") {
                LyricsScreen(
                    state = lyricsState,
                    playerState = playerState,
                    onSeek = playerViewModel::seekTo,
                    onBack = navController::popBackStack,
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(
    profile: ServerProfile,
    state: LibraryUiState,
    viewModel: MusicViewModel,
    onRefresh: () -> Unit,
    onAlbum: (String) -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
    recommendations: List<RecommendationShelf>,
    onDiscovery: () -> Unit,
    onRefreshRecommendations: () -> Unit,
) {
    val isLibraryEmpty = state.albums.isEmpty() && state.artists.isEmpty() && state.songs.isEmpty()
    val newTaste = recommendations.firstOrNull { it.id == "new-taste" }
        ?: recommendations.firstOrNull { it.id == "random" }
    val frequent = recommendations.firstOrNull { it.id == "frequent" }
    val heroSongs = remember(newTaste, frequent, recommendations) {
        (newTaste?.songs.orEmpty() + frequent?.songs.orEmpty() + recommendations.flatMap { it.songs })
            .distinctBy(Song::id)
            .take(4)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.home_greeting, profile.username),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.primary,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.AccountCircle, contentDescription = stringResource(R.string.nav_my))
                    }
                }
            }
        }
        item {
            DiscoveryHeroCard(
                songs = heroSongs,
                viewModel = viewModel,
                onDiscovery = onDiscovery,
            )
        }
        if ((state.isInitializing || state.isRefreshing) && isLibraryEmpty) {
            item { LoadingBlock() }
        } else if (isLibraryEmpty) {
            item {
                EmptyBlock(
                    text = state.errorMessage ?: stringResource(R.string.empty_library),
                    action = stringResource(R.string.refresh),
                    onAction = onRefresh,
                )
            }
        } else {
            item {
                HomeSectionHeader(
                    title = stringResource(R.string.home_listen_today),
                    subtitle = stringResource(R.string.home_listen_today_subtitle),
                )
            }
            item {
                RecommendationSceneCards(
                    newTaste = newTaste,
                    frequent = frequent,
                    viewModel = viewModel,
                    onRefresh = onRefreshRecommendations,
                    onPlay = onPlay,
                )
            }
            if (state.albums.isNotEmpty()) {
                item {
                    HomeSectionHeader(
                        title = stringResource(R.string.recently_added),
                        subtitle = stringResource(R.string.recently_added_subtitle),
                    )
                }
                item {
                    AlbumShelf(
                        albums = state.albums.take(20),
                        viewModel = viewModel,
                        onAlbum = onAlbum,
                    )
                }
            }
        }
    }
}

@Composable
private fun DiscoveryHeroCard(
    songs: List<Song>,
    viewModel: MusicViewModel,
    onDiscovery: () -> Unit,
) {
    val gradient = Brush.linearGradient(
        listOf(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.secondaryContainer,
        ),
    )
    ElevatedCard(
        onClick = onDiscovery,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(gradient)
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.discover_music),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    stringResource(R.string.discover_music_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.go_discover),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Icon(
                        Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            RecommendationCoverMosaic(songs = songs, viewModel = viewModel)
        }
    }
}

@Composable
private fun RecommendationSceneCards(
    newTaste: RecommendationShelf?,
    frequent: RecommendationShelf?,
    viewModel: MusicViewModel,
    onRefresh: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RecommendationSceneCard(
            modifier = Modifier.weight(1f),
            title = stringResource(R.string.home_new_taste),
            description = stringResource(
                if (newTaste?.songs.isNullOrEmpty()) {
                    R.string.home_new_taste_empty
                } else {
                    R.string.home_new_taste_subtitle
                },
            ),
            songs = newTaste?.songs.orEmpty(),
            viewModel = viewModel,
            actionIcon = Icons.Rounded.PlayArrow,
            actionDescription = stringResource(R.string.play_action),
            actionEnabled = !newTaste?.songs.isNullOrEmpty(),
            onAction = { newTaste?.songs?.takeIf(List<Song>::isNotEmpty)?.let { onPlay(it, 0) } },
            secondaryAction = onRefresh,
        )
        RecommendationSceneCard(
            modifier = Modifier.weight(1f),
            title = stringResource(R.string.home_frequent),
            description = stringResource(
                if (frequent?.songs.isNullOrEmpty()) {
                    R.string.home_frequent_empty
                } else {
                    R.string.home_frequent_subtitle
                },
            ),
            songs = frequent?.songs.orEmpty(),
            viewModel = viewModel,
            actionIcon = Icons.Rounded.PlayArrow,
            actionDescription = stringResource(R.string.play_action),
            actionEnabled = !frequent?.songs.isNullOrEmpty(),
            onAction = { frequent?.songs?.takeIf(List<Song>::isNotEmpty)?.let { onPlay(it, 0) } },
        )
    }
}

@Composable
private fun RecommendationSceneCard(
    modifier: Modifier,
    title: String,
    description: String,
    songs: List<Song>,
    viewModel: MusicViewModel,
    actionIcon: ImageVector,
    actionDescription: String,
    actionEnabled: Boolean,
    onAction: () -> Unit,
    secondaryAction: (() -> Unit)? = null,
) {
    ElevatedCard(
        modifier = modifier.height(218.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            RecommendationCoverPair(songs = songs, viewModel = viewModel)
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (secondaryAction != null) {
                    TextButton(onClick = secondaryAction) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.change_batch))
                    }
                } else {
                    Spacer(Modifier.width(1.dp))
                }
                FilledTonalIconButton(onClick = onAction, enabled = actionEnabled) {
                    Icon(actionIcon, contentDescription = actionDescription)
                }
            }
        }
    }
}

@Composable
private fun RecommendationCoverMosaic(songs: List<Song>, viewModel: MusicViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(2) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(2) { column ->
                    RecommendationCoverCell(
                        song = songs.getOrNull(row * 2 + column),
                        viewModel = viewModel,
                        modifier = Modifier.size(54.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RecommendationCoverPair(songs: List<Song>, viewModel: MusicViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(2) { index ->
            RecommendationCoverCell(
                song = songs.getOrNull(index),
                viewModel = viewModel,
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f),
            )
        }
    }
}

@Composable
private fun RecommendationCoverCell(
    song: Song?,
    viewModel: MusicViewModel,
    modifier: Modifier,
) {
    if (song != null) {
        CoverArt(song.displayCoverArtId(), song.title, viewModel, modifier, fallbackSeed = song.id)
    } else {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.MusicNote, contentDescription = null)
            }
        }
    }
}

@Composable
private fun MyScreen(
    profile: ServerProfile,
    playlists: List<Playlist>,
    viewModel: MusicViewModel,
    likedCount: Int,
    offlineCount: Int,
    downloadCount: Int,
    isConnecting: Boolean,
    connectionError: String?,
    onFavorites: () -> Unit,
    onListeningProfile: () -> Unit,
    onPlaylist: (String) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onOffline: () -> Unit,
    onDownloads: () -> Unit,
    onSettings: () -> Unit,
) {
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    val connectionState = when {
        isConnecting -> ServerConnectionState.CONNECTING
        connectionError != null -> ServerConnectionState.ERROR
        else -> ServerConnectionState.CONNECTED
    }
    val serverStatusLabel = stringResource(connectionState.label, profile.serverType)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            ClearTunePageHeader(title = stringResource(R.string.nav_my))
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.AccountCircle,
                        contentDescription = null,
                        modifier = Modifier.size(60.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(profile.username, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .background(connectionState.color, CircleShape),
                            )
                            Spacer(Modifier.width(7.dp))
                            Text(
                                text = listOf(serverStatusLabel, profile.serverVersion)
                                    .filter(String::isNotBlank)
                                    .joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    FilledTonalIconButton(onClick = onSettings) {
                        Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.settings))
                    }
                }
            }
        }
        item {
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, top = 14.dp, end = 20.dp),
            ) {
                MyEntry(
                    icon = Icons.Rounded.Insights,
                    title = stringResource(R.string.listening_profile),
                    subtitle = stringResource(R.string.listening_profile_subtitle),
                    onClick = onListeningProfile,
                )
            }
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MyMetricCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.Favorite,
                    value = likedCount.toString(),
                    label = stringResource(R.string.favorites),
                    onClick = onFavorites,
                )
                MyMetricCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.AutoMirrored.Rounded.QueueMusic,
                    value = playlists.size.toString(),
                    label = stringResource(R.string.playlists),
                )
            }
        }
        item { MySectionTitle(stringResource(R.string.offline_and_downloads)) }
        item {
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    MyDownloadEntry(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.OfflinePin,
                        title = stringResource(R.string.offline_music),
                        subtitle = stringResource(R.string.offline_song_count, offlineCount),
                        onClick = onOffline,
                    )
                    Box(
                        modifier = Modifier
                            .padding(vertical = 18.dp)
                            .width(1.dp)
                            .height(58.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                    MyDownloadEntry(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.Download,
                        title = stringResource(R.string.downloads),
                        subtitle = stringResource(R.string.download_task_count, downloadCount),
                        onClick = onDownloads,
                    )
                }
            }
        }
        item { MySectionTitle(stringResource(R.string.playlists)) }
        items(playlists, key = Playlist::id) { playlist ->
            PlaylistRow(playlist = playlist, viewModel = viewModel, onClick = onPlaylist)
        }
        item(key = "create-playlist") {
            NewPlaylistRow(onClick = { showCreatePlaylist = true })
        }
    }

    if (showCreatePlaylist) {
        AlertDialog(
            onDismissRequest = { showCreatePlaylist = false },
            title = { Text(stringResource(R.string.new_playlist)) },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    label = { Text(stringResource(R.string.playlist_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onCreatePlaylist(newPlaylistName)
                        newPlaylistName = ""
                        showCreatePlaylist = false
                    },
                    enabled = newPlaylistName.isNotBlank(),
                ) { Text(stringResource(R.string.create_action)) }
            },
            dismissButton = {
                TextButton(onClick = { showCreatePlaylist = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun MyMetricCard(
    modifier: Modifier,
    icon: ImageVector,
    value: String,
    label: String,
    onClick: (() -> Unit)? = null,
) {
    ElevatedCard(
        modifier = modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ClearTuneIconTile(icon)
            Column {
                Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

private enum class ServerConnectionState(val color: Color, val label: Int) {
    CONNECTED(Color(0xFF2E7D32), R.string.server_status_connected),
    CONNECTING(Color(0xFFF9A825), R.string.server_status_connecting),
    ERROR(Color(0xFFC62828), R.string.server_status_error),
}

@Composable
private fun MySectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 6.dp),
    )
}

@Composable
private fun MyEntry(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(subtitle) },
        leadingContent = { ClearTuneIconTile(icon) },
        trailingContent = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun NewPlaylistRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(52.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.new_playlist),
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.new_playlist), fontWeight = FontWeight.Medium)
            Text(
                stringResource(R.string.new_playlist_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null)
    }
    HorizontalDivider(modifier = Modifier.padding(start = 86.dp))
}

@Composable
private fun MyDownloadEntry(
    modifier: Modifier,
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Box(modifier = Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(title, fontWeight = FontWeight.Medium, maxLines = 1)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FavoriteSongsScreen(
    songs: List<Song>,
    viewModel: MusicViewModel,
    onBack: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
) {
    Scaffold(
        topBar = {
            ClearTuneTopAppBar(title = stringResource(R.string.favorites), onBack = onBack)
        },
    ) { padding ->
        if (songs.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.no_favorite_songs), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(modifier = Modifier.padding(padding)) {
                items(songs, key = Song::id) { song ->
                    SongRow(
                        song = song,
                        onClick = { onPlay(songs, songs.indexOf(song)) },
                        onFavorite = { viewModel.toggleSongFavorite(song) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchScreen(
    state: SearchUiState,
    recentSearches: List<String>,
    genres: List<String>,
    playlists: List<Playlist>,
    viewModel: MusicViewModel,
    onAlbum: (String) -> Unit,
    onArtist: (String) -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
    onPlaylist: (String) -> Unit,
) {
    var selectedResultFilter by rememberSaveable { mutableIntStateOf(SearchResultFilter.ALL.ordinal) }
    LaunchedEffect(state.query) { selectedResultFilter = SearchResultFilter.ALL.ordinal }
    val recommendedSuggestions = genres.ifEmpty {
        listOf(
            stringResource(R.string.genre_pop),
            stringResource(R.string.genre_rock),
            stringResource(R.string.genre_jazz),
            stringResource(R.string.genre_classical),
        )
    }
    val suggestions = (state.suggestedQueries + recommendedSuggestions).distinct().take(8)
    val selectedFilter = SearchResultFilter.entries.getOrElse(selectedResultFilter) {
        SearchResultFilter.ALL
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            val inputQuery by viewModel.searchInput.collectAsStateWithLifecycle()
            ClearTunePageHeader(title = stringResource(R.string.nav_search))
            OutlinedTextField(
                value = inputQuery,
                onValueChange = viewModel::updateSearchQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                shape = MaterialTheme.shapes.large,
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.search_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.submitSearch() }),
            )
        }
        when {
            state.query.isBlank() -> {
                if (recentSearches.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SectionTitle(stringResource(R.string.recent_searches))
                            TextButton(onClick = viewModel::clearRecentSearches) {
                                Text(stringResource(R.string.clear_action))
                            }
                        }
                    }
                    items(recentSearches, key = { "recent-$it" }) { query ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.updateSearchQuery(query) }
                                .padding(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AssistChip(
                                onClick = { viewModel.updateSearchQuery(query) },
                                label = { Text(query) },
                            )
                            TextButton(onClick = { viewModel.removeRecentSearch(query) }) {
                                Text(stringResource(R.string.remove_action))
                            }
                        }
                    }
                }
                if (suggestions.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.search_recommended)) }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(suggestions.take(8), key = { "suggestion-$it" }) { genre ->
                                FilterChip(
                                    selected = false,
                                    onClick = { viewModel.updateSearchQuery(genre) },
                                    label = { Text(genre) },
                                )
                            }
                        }
                    }
                }
            }
            state.isSearching && state.results.artists.isEmpty() && state.results.albums.isEmpty() &&
                state.results.songs.isEmpty() && state.results.playlists.isEmpty() -> {
                item { LoadingBlock(stringResource(R.string.searching)) }
            }
            state.results.artists.isEmpty() && state.results.albums.isEmpty() &&
                state.results.songs.isEmpty() && state.results.playlists.isEmpty() -> {
                if (state.suggestedQueries.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.search_did_you_mean)) }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(state.suggestedQueries, key = { "correction-$it" }) { suggestion ->
                                AssistChip(
                                    onClick = { viewModel.updateSearchQuery(suggestion) },
                                    label = { Text(suggestion) },
                                )
                            }
                        }
                    }
                }
                item { EmptyBlock(state.errorMessage ?: stringResource(R.string.no_results)) }
            }
            else -> {
                if (state.isSearching) {
                    item {
                        Text(
                            stringResource(R.string.searching_server_more),
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                state.errorMessage?.let { message ->
                    item {
                        Surface(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                message,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
                item {
                    val filters = listOf(
                        Triple(SearchResultFilter.ALL, stringResource(R.string.search_filter_all), null),
                        Triple(SearchResultFilter.SONGS, stringResource(R.string.songs), SearchCategory.SONGS),
                        Triple(SearchResultFilter.ALBUMS, stringResource(R.string.albums), SearchCategory.ALBUMS),
                        Triple(SearchResultFilter.ARTISTS, stringResource(R.string.artists), SearchCategory.ARTISTS),
                        Triple(SearchResultFilter.PLAYLISTS, stringResource(R.string.playlists), SearchCategory.PLAYLISTS),
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(filters, key = { it.first.name }) { (filter, label, category) ->
                            FilterChip(
                                selected = selectedFilter == filter,
                                onClick = { selectedResultFilter = filter.ordinal },
                                label = {
                                    Text(
                                        category?.let {
                                            stringResource(
                                                R.string.search_filter_with_count,
                                                label,
                                                state.results.resultCount(it),
                                            )
                                        } ?: label,
                                    )
                                },
                            )
                        }
                    }
                }
                val selectedCategory = when (selectedFilter) {
                    SearchResultFilter.SONGS -> SearchCategory.SONGS
                    SearchResultFilter.ALBUMS -> SearchCategory.ALBUMS
                    SearchResultFilter.ARTISTS -> SearchCategory.ARTISTS
                    SearchResultFilter.PLAYLISTS -> SearchCategory.PLAYLISTS
                    SearchResultFilter.ALL -> null
                }
                if (selectedCategory != null && state.results.resultCount(selectedCategory) == 0) {
                    item {
                        EmptyBlock(
                            stringResource(
                                R.string.search_no_results_in_category,
                                when (selectedFilter) {
                                    SearchResultFilter.SONGS -> stringResource(R.string.songs)
                                    SearchResultFilter.ALBUMS -> stringResource(R.string.albums)
                                    SearchResultFilter.ARTISTS -> stringResource(R.string.artists)
                                    SearchResultFilter.PLAYLISTS -> stringResource(R.string.playlists)
                                    SearchResultFilter.ALL -> stringResource(R.string.search_filter_all)
                                },
                            ),
                        )
                    }
                }
                if (
                    state.suggestedQueries.isNotEmpty() &&
                    selectedFilter == SearchResultFilter.ALL
                ) {
                    item { SectionTitle(stringResource(R.string.search_related_suggestions)) }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(state.suggestedQueries, key = { "related-$it" }) { suggestion ->
                                AssistChip(
                                    onClick = { viewModel.updateSearchQuery(suggestion) },
                                    label = { Text(suggestion) },
                                )
                            }
                        }
                    }
                }
                if (
                    state.results.songs.isNotEmpty() &&
                    selectedFilter in setOf(SearchResultFilter.ALL, SearchResultFilter.SONGS)
                ) {
                    item { SectionTitle(stringResource(R.string.songs)) }
                    items(
                        state.results.songs.take(state.visibleSongCount),
                        key = { "song-${it.id}" },
                    ) { song ->
                        SongRow(
                            song = song,
                            onClick = { onPlay(state.results.songs, state.results.songs.indexOf(song)) },
                            onFavorite = { viewModel.toggleSongFavorite(song) },
                            trailingContent = {
                                AddToPlaylistAction(
                                    playlists = playlists,
                                    viewModel = viewModel,
                                    onAddToPlaylist = { playlistId ->
                                        viewModel.addPlaylistSong(playlistId, song.id)
                                    },
                                )
                            },
                        )
                    }
                    if (state.results.songs.size > state.visibleSongCount || state.hasMoreSongs) {
                        item(key = "more-songs") {
                            SearchLoadMoreButton(
                                loading = state.loadingMoreCategory == SearchCategory.SONGS,
                                onClick = { viewModel.loadMoreSearch(SearchCategory.SONGS) },
                            )
                        }
                    }
                }
                if (
                    state.results.artists.isNotEmpty() &&
                    selectedFilter in setOf(SearchResultFilter.ALL, SearchResultFilter.ARTISTS)
                ) {
                    item { SectionTitle(stringResource(R.string.artists)) }
                    items(
                        state.results.artists.take(state.visibleArtistCount),
                        key = { "artist-${it.id}" },
                    ) {
                        ArtistRow(it, viewModel, onArtist)
                    }
                    if (state.results.artists.size > state.visibleArtistCount || state.hasMoreArtists) {
                        item(key = "more-artists") {
                            SearchLoadMoreButton(
                                loading = state.loadingMoreCategory == SearchCategory.ARTISTS,
                                onClick = { viewModel.loadMoreSearch(SearchCategory.ARTISTS) },
                            )
                        }
                    }
                }
                if (
                    state.results.albums.isNotEmpty() &&
                    selectedFilter in setOf(SearchResultFilter.ALL, SearchResultFilter.ALBUMS)
                ) {
                    item { SectionTitle(stringResource(R.string.albums)) }
                    items(
                        state.results.albums.take(state.visibleAlbumCount),
                        key = { "album-${it.id}" },
                    ) {
                        AlbumListRow(it, viewModel, onAlbum)
                    }
                    if (state.results.albums.size > state.visibleAlbumCount || state.hasMoreAlbums) {
                        item(key = "more-albums") {
                            SearchLoadMoreButton(
                                loading = state.loadingMoreCategory == SearchCategory.ALBUMS,
                                onClick = { viewModel.loadMoreSearch(SearchCategory.ALBUMS) },
                            )
                        }
                    }
                }
                if (
                    state.results.playlists.isNotEmpty() &&
                    selectedFilter in setOf(SearchResultFilter.ALL, SearchResultFilter.PLAYLISTS)
                ) {
                    item { SectionTitle(stringResource(R.string.playlists)) }
                    items(
                        state.results.playlists.take(state.visiblePlaylistCount),
                        key = { "playlist-${it.id}" },
                    ) { playlist ->
                        Text(
                            playlist.name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPlaylist(playlist.id) }
                                .padding(horizontal = 12.dp, vertical = 14.dp),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    if (state.results.playlists.size > state.visiblePlaylistCount) {
                        item(key = "more-playlists") {
                            SearchLoadMoreButton(
                                loading = false,
                                onClick = { viewModel.loadMoreSearch(SearchCategory.PLAYLISTS) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchLoadMoreButton(
    loading: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
        } else {
            OutlinedButton(onClick = onClick) {
                Text(stringResource(R.string.load_more_results))
            }
        }
    }
}

@Composable
private fun LibraryScreen(
    state: LibraryUiState,
    genres: List<String>,
    folderState: FolderUiState,
    viewModel: MusicViewModel,
    onRefresh: () -> Unit,
    onAlbum: (String) -> Unit,
    onArtist: (String) -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
    onPlayNext: (Song) -> Unit,
    onDownload: (Song) -> Unit,
    onGenre: (String) -> Unit,
) {
    val labels = listOf(
        stringResource(R.string.songs),
        stringResource(R.string.albums),
        stringResource(R.string.artists),
        stringResource(R.string.genres),
        stringResource(R.string.folders),
    )
    var savedSelectedTab by rememberSaveable { mutableIntStateOf(1) }
    val selectedTab = savedSelectedTab.coerceIn(labels.indices)
    var savedSongSort by rememberSaveable { mutableIntStateOf(LibrarySongSort.TITLE.ordinal) }
    val songSort = LibrarySongSort.entries.getOrElse(savedSongSort) { LibrarySongSort.TITLE }
    var showSongSortMenu by remember { mutableStateOf(false) }
    var showAlphabetIndex by remember { mutableStateOf(false) }
    var isAlphabetIndexDragging by remember { mutableStateOf(false) }
    var alphabetIndexHeightPx by remember { mutableIntStateOf(1) }
    val indexedSongs = remember(state.songs, songSort) {
        buildLibrarySongIndex(state.songs, songSort)
    }
    val songListState = rememberLazyListState()
    val songListScope = rememberCoroutineScope()
    val showSongScrollToTop by remember {
        derivedStateOf { songListState.firstVisibleItemIndex >= 8 }
    }
    LaunchedEffect(savedSelectedTab, labels.size) {
        if (savedSelectedTab !in labels.indices) savedSelectedTab = 0
    }
    LaunchedEffect(
        songListState.isScrollInProgress,
        indexedSongs.anchors,
        isAlphabetIndexDragging,
    ) {
        if (indexedSongs.anchors.isEmpty()) {
            showAlphabetIndex = false
            isAlphabetIndexDragging = false
        } else if (songListState.isScrollInProgress || isAlphabetIndexDragging) {
            showAlphabetIndex = true
        } else if (showAlphabetIndex) {
            delay(1_100)
            if (!songListState.isScrollInProgress && !isAlphabetIndexDragging) {
                showAlphabetIndex = false
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        ClearTunePageHeader(
            title = stringResource(R.string.nav_library),
            subtitle = state.librarySyncSubtitle(),
        ) {
            FilledTonalIconButton(onClick = onRefresh, enabled = !state.isRefreshing) {
                Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.refresh))
            }
        }
        if (state.isRefreshing || state.errorMessage != null) {
            LibrarySyncBanner(state = state, onRefresh = onRefresh)
        }
        PrimaryTabRow(selectedTabIndex = selectedTab) {
            labels.forEachIndexed { index, label ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { savedSelectedTab = index },
                    text = {
                        Text(
                            text = label,
                            maxLines = 1,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    },
                )
            }
        }
        val contentState = when {
            (state.isInitializing || state.isRefreshing) &&
                state.albums.isEmpty() && state.artists.isEmpty() && state.songs.isEmpty() -> -2
            state.albums.isEmpty() && state.artists.isEmpty() && state.songs.isEmpty() -> -1
            else -> selectedTab
        }
        AnimatedContent(
            targetState = contentState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            transitionSpec = {
                when {
                    initialState < 0 || targetState < 0 ->
                        fadeIn(ClearTuneMotion.standard()) togetherWith fadeOut(ClearTuneMotion.quick())
                    targetState > initialState ->
                        (fadeIn(ClearTuneMotion.standard()) + slideInHorizontally(
                            initialOffsetX = { it / 10 },
                            animationSpec = ClearTuneMotion.standard(),
                        )) togetherWith (fadeOut(ClearTuneMotion.quick()) + slideOutHorizontally(
                            targetOffsetX = { -it / 14 },
                            animationSpec = ClearTuneMotion.standard(),
                        ))
                    else ->
                        (fadeIn(ClearTuneMotion.standard()) + slideInHorizontally(
                            initialOffsetX = { -it / 10 },
                            animationSpec = ClearTuneMotion.standard(),
                        )) togetherWith (fadeOut(ClearTuneMotion.quick()) + slideOutHorizontally(
                            targetOffsetX = { it / 14 },
                            animationSpec = ClearTuneMotion.standard(),
                        ))
                }
            },
            label = "libraryContent",
        ) { visibleContent ->
            when (visibleContent) {
                -2 -> LoadingBlock()
                -1 -> EmptyBlock(
                    text = state.errorMessage ?: stringResource(R.string.empty_library),
                    action = stringResource(R.string.refresh),
                    onAction = onRefresh,
                )
                0 -> Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = songListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = 10.dp,
                            bottom = 80.dp,
                        ),
                    ) {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        stringResource(R.string.all_songs),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        stringResource(R.string.song_count_compact, indexedSongs.songs.size),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Box(modifier = Modifier.weight(1f)) {
                                        FilledTonalButton(
                                            onClick = { showSongSortMenu = true },
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Icon(
                                                Icons.AutoMirrored.Rounded.Sort,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                stringResource(R.string.sort_current, songSort.label()),
                                                maxLines = 1,
                                            )
                                            Icon(
                                                Icons.Rounded.ArrowDropDown,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = showSongSortMenu,
                                            onDismissRequest = { showSongSortMenu = false },
                                        ) {
                                            LibrarySongSort.entries.forEach { option ->
                                                val selected = option == songSort
                                                DropdownMenuItem(
                                                    text = { Text(option.label()) },
                                                    leadingIcon = {
                                                        Icon(
                                                            if (selected) {
                                                                Icons.Rounded.CheckCircle
                                                            } else {
                                                                Icons.Rounded.RadioButtonUnchecked
                                                            },
                                                            contentDescription = null,
                                                            tint = if (selected) {
                                                                MaterialTheme.colorScheme.primary
                                                            } else {
                                                                MaterialTheme.colorScheme.onSurfaceVariant
                                                            },
                                                        )
                                                    },
                                                    onClick = {
                                                        savedSongSort = option.ordinal
                                                        showSongSortMenu = false
                                                        songListScope.launch { songListState.scrollToItem(0) }
                                                    },
                                                )
                                            }
                                        }
                                    }
                                    FilledTonalButton(
                                        onClick = {
                                            if (indexedSongs.songs.isNotEmpty()) {
                                                onPlay(indexedSongs.songs.shuffled(), 0)
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        enabled = indexedSongs.songs.isNotEmpty(),
                                    ) {
                                        Icon(
                                            Icons.Rounded.Shuffle,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.shuffle_all), maxLines = 1)
                                    }
                                }
                            }
                        }
                        itemsIndexed(indexedSongs.songs, key = { _, song -> song.id }) { index, song ->
                            LibrarySongRow(
                                song = song,
                                viewModel = viewModel,
                                onClick = { onPlay(indexedSongs.songs, index) },
                                actions = {
                                    LibrarySongActions(
                                        song = song,
                                        playlists = state.playlists,
                                        viewModel = viewModel,
                                        onAddToPlaylist = { playlistId ->
                                            viewModel.addPlaylistSong(playlistId, song.id)
                                        },
                                        onToggleLike = { viewModel.toggleSongFavorite(song) },
                                        onPlayNext = { onPlayNext(song) },
                                        onDownload = { onDownload(song) },
                                    )
                                },
                            )
                        }
                    }
                    if (showAlphabetIndex && indexedSongs.anchors.isNotEmpty()) {
                        val visibleSongIndex = (songListState.firstVisibleItemIndex - 1).coerceAtLeast(0)
                        val activeAnchor = indexedSongs.anchors.lastOrNull {
                            it.songIndex <= visibleSongIndex
                        } ?: indexedSongs.anchors.first()
                        Surface(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 42.dp)
                                .size(56.dp),
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.94f),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = activeAnchor.label,
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                    }
                    if (indexedSongs.anchors.isNotEmpty()) {
                        val visibleSongIndex = (songListState.firstVisibleItemIndex - 1).coerceAtLeast(0)
                        val activeAnchor = indexedSongs.anchors.lastOrNull {
                            it.songIndex <= visibleSongIndex
                        } ?: indexedSongs.anchors.first()
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .width(if (showAlphabetIndex) 44.dp else 16.dp)
                                .heightIn(max = 440.dp)
                                .onSizeChanged { alphabetIndexHeightPx = it.height.coerceAtLeast(1) }
                                .pointerInput(indexedSongs.anchors, alphabetIndexHeightPx) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        val pointerId = down.id
                                        var lastAnchorIndex = -1
                                        var scrollJob: Job? = null

                                        fun scrollToAnchorAt(y: Float) {
                                            val normalizedY = y.coerceIn(
                                                minimumValue = 0f,
                                                maximumValue = (alphabetIndexHeightPx - 1).coerceAtLeast(0).toFloat(),
                                            )
                                            val anchorIndex = (
                                                normalizedY / alphabetIndexHeightPx * indexedSongs.anchors.size
                                            ).toInt().coerceIn(indexedSongs.anchors.indices)
                                            if (anchorIndex != lastAnchorIndex) {
                                                lastAnchorIndex = anchorIndex
                                                val anchor = indexedSongs.anchors[anchorIndex]
                                                scrollJob?.cancel()
                                                scrollJob = songListScope.launch {
                                                    songListState.scrollToItem(anchor.songIndex + 1)
                                                }
                                            }
                                        }

                                        isAlphabetIndexDragging = true
                                        showAlphabetIndex = true
                                        down.consume()
                                        scrollToAnchorAt(down.position.y)
                                        try {
                                            do {
                                                val event = awaitPointerEvent()
                                                val change = event.changes.firstOrNull { it.id == pointerId }
                                                    ?: break
                                                if (change.pressed) {
                                                    change.consume()
                                                    scrollToAnchorAt(change.position.y)
                                                }
                                            } while (change.pressed)
                                        } finally {
                                            isAlphabetIndexDragging = false
                                        }
                                    }
                                },
                        ) {
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .width(28.dp)
                                    .alpha(if (showAlphabetIndex) 1f else 0f),
                                shape = RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                                shadowElevation = 4.dp,
                            ) {
                                Column(
                                    modifier = Modifier.padding(vertical = 6.dp),
                                    verticalArrangement = Arrangement.SpaceEvenly,
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    indexedSongs.anchors.forEach { anchor ->
                                        Text(
                                            text = anchor.label,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 1.dp),
                                            color = if (anchor == activeAnchor) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (anchor == activeAnchor) {
                                                FontWeight.Bold
                                            } else {
                                                FontWeight.Normal
                                            },
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showSongScrollToTop,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 20.dp, bottom = 20.dp),
                        enter = fadeIn(ClearTuneMotion.standard()) + scaleIn(
                            initialScale = 0.82f,
                            animationSpec = ClearTuneMotion.standard(),
                        ),
                        exit = fadeOut(ClearTuneMotion.quick()) + scaleOut(
                            targetScale = 0.82f,
                            animationSpec = ClearTuneMotion.quick(),
                        ),
                    ) {
                        SmallFloatingActionButton(
                            onClick = {
                                songListScope.launch {
                                    songListState.animateScrollToItem(0)
                                }
                            },
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ArrowUpward,
                                contentDescription = stringResource(R.string.scroll_to_top),
                            )
                        }
                    }
                }
                1 -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    gridItems(state.albums, key = { it.id }) { album ->
                        AlbumGridCard(album = album, viewModel = viewModel, onClick = onAlbum)
                    }
                }
                2 -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.artists, key = { it.id }) { ArtistRow(it, viewModel, onArtist) }
                }
                3 -> LazyColumn(Modifier.fillMaxSize()) {
                    items(genres) { genre ->
                        ListItem(
                            headlineContent = { Text(genre) },
                            supportingContent = {
                                Text(
                                    stringResource(
                                        R.string.genre_song_count,
                                        state.songs.count { genreLabelsMatch(it.genre, genre) },
                                    ),
                                )
                            },
                            trailingContent = {
                                Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                            },
                            modifier = Modifier.clickable { onGenre(genre) },
                        )
                        HorizontalDivider()
                    }
                }
                4 -> FolderBrowser(state = folderState, viewModel = viewModel, onPlay = onPlay)
                else -> Unit
            }
        }
    }
}

@Composable
private fun LibraryUiState.librarySyncSubtitle(): String = when {
    isRefreshing -> syncStage.label()
    lastSyncedAt != null -> stringResource(
        R.string.library_last_synced,
        formatLibrarySyncTime(lastSyncedAt),
    )
    else -> stringResource(R.string.library_subtitle)
}

@Composable
private fun LibrarySyncStage.label(): String = stringResource(
    when (this) {
        LibrarySyncStage.LIBRARY -> R.string.library_syncing_media
        LibrarySyncStage.GENRES -> R.string.library_syncing_genres
        LibrarySyncStage.FOLDERS -> R.string.library_syncing_folders
        LibrarySyncStage.IDLE -> R.string.library_syncing
    },
)

@Composable
private fun LibrarySyncBanner(state: LibraryUiState, onRefresh: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = if (state.errorMessage == null) {
            MaterialTheme.colorScheme.surfaceContainer
        } else {
            MaterialTheme.colorScheme.errorContainer
        },
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            if (state.isRefreshing) {
                Text(state.syncStage.label(), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        state.errorMessage.orEmpty(),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    TextButton(onClick = onRefresh) { Text(stringResource(R.string.retry_action)) }
                }
            }
        }
    }
}

@Composable
private fun GenreSongsScreen(
    genre: String,
    songs: List<Song>,
    viewModel: MusicViewModel,
    onBack: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
) {
    Scaffold(topBar = { ClearTuneTopAppBar(title = genre, onBack = onBack) }) { padding ->
        if (songs.isEmpty()) {
            ClearTuneEmptyState(
                modifier = Modifier.fillMaxSize().padding(padding),
                title = genre,
                description = stringResource(R.string.genre_no_songs),
                icon = Icons.Rounded.MusicNote,
            )
        } else {
            LazyColumn(modifier = Modifier.padding(padding)) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.genre_song_count, songs.size))
                        FilledTonalButton(onClick = { onPlay(songs, 0) }) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.play_all))
                        }
                    }
                }
                itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                    SongRow(
                        song = song,
                        onClick = { onPlay(songs, index) },
                        onFavorite = { viewModel.toggleSongFavorite(song) },
                        showTrackNumber = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun LibrarySongSort.label(): String = stringResource(
    when (this) {
        LibrarySongSort.TITLE -> R.string.sort_by_title
        LibrarySongSort.PLAY_COUNT -> R.string.sort_by_play_count
        LibrarySongSort.RECENTLY_ADDED -> R.string.recently_added
    },
)

private fun buildLibrarySongIndex(
    songs: List<Song>,
    sort: LibrarySongSort = LibrarySongSort.TITLE,
): IndexedLibrarySongs {
    if (songs.isEmpty()) return IndexedLibrarySongs(emptyList(), emptyList())

    val locale = Locale.getDefault()
    val alphabet = AlphabeticIndex<Any>(locale)
        .addLabels(Locale.ENGLISH)
        .buildImmutableIndex()
    val collator = Collator.getInstance(locale)
    val titleComparator = Comparator<Song> { first, second ->
        val firstBucket = alphabet.getBucketIndex(first.title)
        val secondBucket = alphabet.getBucketIndex(second.title)
        when {
            firstBucket != secondBucket -> firstBucket.compareTo(secondBucket)
            else -> collator.compare(first.title, second.title)
        }
    }
    val sortedSongs = when (sort) {
        LibrarySongSort.TITLE -> songs.sortedWith(titleComparator)
        LibrarySongSort.PLAY_COUNT -> songs.sortedWith(
            compareByDescending<Song> { it.playCount }.then(titleComparator),
        )
        LibrarySongSort.RECENTLY_ADDED -> songs.sortedWith(
            compareByDescending<Song> { it.createdAt ?: Long.MIN_VALUE }.then(titleComparator),
        )
    }
    if (sort != LibrarySongSort.TITLE) {
        return IndexedLibrarySongs(songs = sortedSongs, anchors = emptyList())
    }
    val anchors = buildList {
        var previousBucket = -1
        sortedSongs.forEachIndexed { index, song ->
            val bucket = alphabet.getBucketIndex(song.title)
            if (bucket != previousBucket) {
                val label = alphabet.getBucket(bucket)?.label.orEmpty().ifBlank { "#" }
                add(LibrarySongIndexAnchor(label = label, songIndex = index))
                previousBucket = bucket
            }
        }
    }
    return IndexedLibrarySongs(songs = sortedSongs, anchors = anchors)
}

@Composable
private fun LibrarySongRow(
    song: Song,
    viewModel: MusicViewModel,
    onClick: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(
            id = song.displayCoverArtId(),
            description = song.title,
            viewModel = viewModel,
            modifier = Modifier.size(50.dp),
            fallbackSeed = song.id,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                song.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
            )
            val subtitle = listOfNotNull(song.displayArtistName(), song.displayAlbumName()).joinToString(" · ")
            if (subtitle.isNotEmpty() || !song.suffix.isNullOrBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                if (subtitle.isNotEmpty()) Text(
                    subtitle,
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                song.suffix?.takeIf(String::isNotBlank)?.let { SongFormatBadge(it) }
            }
        }
        actions()
    }
    HorizontalDivider(modifier = Modifier.padding(start = 82.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun FolderBrowser(
    state: FolderUiState,
    viewModel: MusicViewModel,
    onPlay: (List<Song>, Int) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        if (state.path.isNotEmpty()) {
            item {
                TextButton(onClick = viewModel::folderBack) {
                    Text(stringResource(R.string.folder_back_path, state.path.joinToString(" / ") { it.name }))
                }
            }
        }
        if (state.loading) item { LoadingBlock() }
        if (state.physicalBrowseUnsupported) {
            item {
                EmptyBlock(
                    text = stringResource(R.string.folder_browse_unsupported),
                    action = stringResource(R.string.folder_back_to_roots),
                    onAction = viewModel::folderBack,
                )
            }
        } else {
            state.errorMessage?.let { item { EmptyBlock(it) } }
        }
        val folders = if (state.path.isEmpty()) state.roots else state.folders
        if (!state.loading && !state.physicalBrowseUnsupported && state.errorMessage == null &&
            folders.isEmpty() && state.songs.isEmpty()
        ) {
            item {
                EmptyBlock(
                    text = stringResource(R.string.empty_folders),
                    action = stringResource(R.string.refresh),
                    onAction = viewModel::refresh,
                )
            }
        }
        items(folders, key = { "folder-${it.id}" }) { folder ->
            ListItem(
                headlineContent = { Text(folder.name) },
                leadingContent = { Icon(Icons.Rounded.Folder, contentDescription = null) },
                trailingContent = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                modifier = Modifier.clickable { viewModel.openFolder(folder) },
            )
            HorizontalDivider()
        }
        items(state.songs, key = { "folder-song-${it.id}" }) { song ->
            SongRow(
                song = song,
                onClick = { onPlay(state.songs, state.songs.indexOf(song)) },
                onFavorite = { viewModel.toggleSongFavorite(song) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumDetailScreen(
    state: DetailUiState,
    playlists: List<Playlist>,
    viewModel: MusicViewModel,
    onBack: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
    onDownload: (List<Song>) -> Unit,
) {
    DetailScaffold(title = "", onBack = onBack) {
        if (state.isLoading) {
            item { LoadingBlock() }
        } else {
            state.album?.let { album ->
                item {
                    DetailHeader(
                        album.name,
                        album.artistName,
                        album.coverArtId,
                        viewModel,
                        fallbackSeed = album.id,
                        favorite = album.starredAt != null,
                        onFavorite = { viewModel.toggleAlbumFavorite(album) },
                    )
                    DetailActions(
                        onPlay = { onPlay(state.songs, 0) },
                        onDownload = { onDownload(state.songs) },
                    )
                }
            }
            state.errorMessage?.let { message -> item { EmptyBlock(message) } }
            items(state.songs, key = { it.id }) { song ->
                SongRow(
                    song = song,
                    onClick = { onPlay(state.songs, state.songs.indexOf(song)) },
                    onFavorite = { viewModel.toggleSongFavorite(song) },
                    trailingContent = {
                        AddToPlaylistAction(
                            playlists = playlists,
                            viewModel = viewModel,
                            onAddToPlaylist = { playlistId ->
                                viewModel.addPlaylistSong(playlistId, song.id)
                            },
                        )
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArtistDetailScreen(
    state: DetailUiState,
    albums: List<Album>,
    viewModel: MusicViewModel,
    onAlbum: (String) -> Unit,
    onBack: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
    onDownload: (List<Song>) -> Unit,
) {
    DetailScaffold(title = "", onBack = onBack) {
        if (state.isLoading) item { LoadingBlock() } else {
            state.artist?.let { artist ->
                item {
                    DetailHeader(
                        artist.name,
                        stringResource(R.string.album_count, artist.albumCount),
                        artist.coverArtId,
                        viewModel,
                        favorite = artist.starredAt != null,
                        onFavorite = { viewModel.toggleArtistFavorite(artist) },
                    )
                }
            }
            state.errorMessage?.let { message -> item { EmptyBlock(message) } }
            if (state.songs.isNotEmpty()) {
                item {
                    SectionTitle(stringResource(R.string.popular_songs))
                    DetailActions(
                        onPlay = { onPlay(state.songs, 0) },
                        onDownload = { onDownload(state.songs) },
                    )
                }
                items(state.songs, key = { "artist-song-${it.id}" }) { song ->
                    SongRow(
                        song,
                        { onPlay(state.songs, state.songs.indexOf(song)) },
                        { viewModel.toggleSongFavorite(song) },
                    )
                }
                item { SectionTitle(stringResource(R.string.albums)) }
            }
            items(albums, key = { it.id }) { AlbumListRow(it, viewModel, onAlbum) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistDetailScreen(
    state: DetailUiState,
    viewModel: MusicViewModel,
    onBack: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
    onDownload: (List<Song>) -> Unit,
    allSongs: List<Song>,
    onRename: (String, String) -> Unit,
    onAddSong: (String, String) -> Unit,
    onRemoveSongs: (String, List<Int>) -> Unit,
    onDelete: (String, () -> Unit) -> Unit,
) {
    var showRename by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var isSelecting by remember(state.playlist?.id) { mutableStateOf(false) }
    var selectedIndexes by remember(state.playlist?.id) { mutableStateOf(emptySet<Int>()) }
    var showRemoveSelectedConfirmation by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var showMoreActions by remember { mutableStateOf(false) }
    var newName by remember(state.playlist?.id) { mutableStateOf(state.playlist?.name.orEmpty()) }
    BackHandler(enabled = isSelecting) {
        isSelecting = false
        selectedIndexes = emptySet()
    }
    DetailScaffold(
        title = if (isSelecting) {
            stringResource(R.string.selected_songs_count, selectedIndexes.size)
        } else {
            state.playlist?.name.orEmpty()
        },
        onBack = {
            if (isSelecting) {
                isSelecting = false
                selectedIndexes = emptySet()
            } else {
                onBack()
            }
        },
        bottomBar = {
            if (isSelecting) {
                PlaylistSelectionBar(
                    allSelected = selectedIndexes.size == state.songs.size && state.songs.isNotEmpty(),
                    removeEnabled = selectedIndexes.isNotEmpty(),
                    onSelectAll = {
                        selectedIndexes = if (selectedIndexes.size == state.songs.size) {
                            emptySet()
                        } else {
                            state.songs.indices.toSet()
                        }
                    },
                    onRemove = { showRemoveSelectedConfirmation = true },
                )
            }
        },
    ) {
        if (state.isLoading) item { LoadingBlock() } else {
            state.playlist?.let { playlist ->
                item {
                    DetailHeader(
                        playlist.name,
                        stringResource(R.string.song_count, playlist.songCount),
                        playlist.coverArtId,
                        viewModel,
                    )
                    if (!isSelecting) {
                        PlaylistDetailActions(
                            hasSongs = state.songs.isNotEmpty(),
                            onPlay = { onPlay(state.songs, 0) },
                            onDownload = { onDownload(state.songs) },
                            onAdd = { showAdd = true },
                            moreExpanded = showMoreActions,
                            onMoreExpandedChange = { showMoreActions = it },
                            onManage = {
                                showMoreActions = false
                                isSelecting = true
                                selectedIndexes = emptySet()
                            },
                            onRename = {
                                showMoreActions = false
                                showRename = true
                            },
                            onDelete = {
                                showMoreActions = false
                                showDeleteConfirmation = true
                            },
                        )
                    }
                }
            }
            state.errorMessage?.let { message -> item { EmptyBlock(message) } }
            itemsIndexed(state.songs, key = { index, song -> "${song.id}-$index" }) { index, song ->
                val isSelected = index in selectedIndexes
                SongRow(
                    song = song,
                    onClick = {
                        if (isSelecting) {
                            selectedIndexes = if (isSelected) selectedIndexes - index else selectedIndexes + index
                        } else {
                            onPlay(state.songs, index)
                        }
                    },
                    onLongClick = if (isSelecting) null else ({
                        isSelecting = true
                        selectedIndexes = setOf(index)
                    }),
                    onFavorite = if (isSelecting) null else ({ viewModel.toggleSongFavorite(song) }),
                    showFileType = true,
                    trailingContent = if (isSelecting) {
                        {
                            IconButton(
                                onClick = {
                                    selectedIndexes = if (isSelected) {
                                        selectedIndexes - index
                                    } else {
                                        selectedIndexes + index
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = if (isSelected) {
                                        Icons.Rounded.CheckCircle
                                    } else {
                                        Icons.Rounded.RadioButtonUnchecked
                                    },
                                    contentDescription = stringResource(
                                        if (isSelected) R.string.unselect_song else R.string.select_song,
                                    ),
                                )
                            }
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
    val playlist = state.playlist
    if (showRename && playlist != null) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text(stringResource(R.string.rename_playlist)) },
            text = {
                OutlinedTextField(
                    newName,
                    { newName = it },
                    label = { Text(stringResource(R.string.new_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onRename(playlist.id, newName); showRename = false },
                    enabled = newName.isNotBlank(),
                ) { Text(stringResource(R.string.save_action)) }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (showAdd && playlist != null) {
        val existing = state.songs.map(Song::id).toSet()
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text(stringResource(R.string.add_songs)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(allSongs.filterNot { it.id in existing }, key = Song::id) { song ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onAddSong(playlist.id, song.id); showAdd = false }
                                .padding(vertical = 10.dp),
                        ) {
                            Column {
                                Text(song.title)
                                song.displayArtistName()?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text(stringResource(R.string.close_action)) } },
        )
    }
    if (showRemoveSelectedConfirmation && playlist != null) {
        AlertDialog(
            onDismissRequest = { showRemoveSelectedConfirmation = false },
            title = { Text(stringResource(R.string.remove_selected_songs_question)) },
            text = { Text(stringResource(R.string.remove_selected_songs_explanation, selectedIndexes.size)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemoveSongs(playlist.id, selectedIndexes.toList())
                        showRemoveSelectedConfirmation = false
                        isSelecting = false
                        selectedIndexes = emptySet()
                    },
                ) {
                    Text(stringResource(R.string.remove_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveSelectedConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    if (showDeleteConfirmation && playlist != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(stringResource(R.string.delete_playlist_question)) },
            text = { Text(stringResource(R.string.delete_playlist_explanation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        onDelete(playlist.id, onBack)
                    },
                ) {
                    Text(
                        stringResource(R.string.delete_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoveryScreen(
    shelves: List<RecommendationShelf>,
    viewModel: MusicViewModel,
    onRefresh: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
    onShelf: (String) -> Unit,
    onBack: () -> Unit,
) {
    var selectedShelfId by rememberSaveable { mutableStateOf<String?>(null) }
    val visibleShelves = selectedShelfId?.let { selected -> shelves.filter { it.id == selected } } ?: shelves
    Scaffold(
        topBar = {
            ClearTuneTopAppBar(
                title = stringResource(R.string.discover_music),
                onBack = onBack,
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.change_batch))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.discover_page_subtitle),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (shelves.isNotEmpty()) {
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            FilterChip(
                                selected = selectedShelfId == null,
                                onClick = { selectedShelfId = null },
                                label = { Text(stringResource(R.string.all)) },
                            )
                        }
                        items(shelves, key = { "filter-${it.id}" }) { shelf ->
                            FilterChip(
                                selected = selectedShelfId == shelf.id,
                                onClick = { selectedShelfId = shelf.id },
                                label = { Text(shelf.title) },
                            )
                        }
                    }
                }
            }
            if (shelves.isEmpty()) {
                item {
                    ClearTuneEmptyState(
                        title = stringResource(R.string.discover_music),
                        description = stringResource(R.string.discovery_empty),
                        icon = Icons.Rounded.Explore,
                    )
                }
            }
            items(visibleShelves, key = RecommendationShelf::id) { shelf ->
                RecommendationShelfCard(
                    shelf = shelf,
                    viewModel = viewModel,
                    onOpen = { onShelf(shelf.id) },
                    onPlay = { if (shelf.songs.isNotEmpty()) onPlay(shelf.songs, 0) },
                )
            }
        }
    }
}

@Composable
private fun RecommendationShelfCard(
    shelf: RecommendationShelf,
    viewModel: MusicViewModel,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(shelf.title, style = MaterialTheme.typography.titleLarge)
                    Text(
                        shelf.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = onOpen) {
                    Text(stringResource(R.string.view_all))
                    Icon(Icons.Rounded.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(4) { index ->
                    RecommendationCoverCell(
                        song = shelf.songs.getOrNull(index),
                        viewModel = viewModel,
                        modifier = Modifier.size(64.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                FilledIconButton(onClick = onPlay, enabled = shelf.songs.isNotEmpty()) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(R.string.play_all))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecommendationShelfScreen(
    shelf: RecommendationShelf?,
    viewModel: MusicViewModel,
    onRefresh: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
    onFavorite: (Song) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            ClearTuneTopAppBar(
                title = shelf?.title ?: stringResource(R.string.discover_music),
                onBack = onBack,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (shelf == null) {
                item {
                    ClearTuneEmptyState(
                        title = stringResource(R.string.discover_music),
                        description = stringResource(R.string.discovery_empty),
                        icon = Icons.Rounded.Explore,
                    )
                }
            } else {
                item {
                    ClearTuneGradientHeader(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        contentPadding = PaddingValues(18.dp),
                    ) {
                        RecommendationCoverPair(songs = shelf.songs, viewModel = viewModel)
                        Text(shelf.title, style = MaterialTheme.typography.headlineSmall)
                        Text(shelf.reason, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            stringResource(R.string.recommendation_song_count, shelf.songs.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            onClick = { if (shelf.songs.isNotEmpty()) onPlay(shelf.songs, 0) },
                            enabled = shelf.songs.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.play_all))
                        }
                        OutlinedButton(onClick = onRefresh, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.change_batch))
                        }
                    }
                }
                items(shelf.songs, key = Song::id) { song ->
                    SongRow(
                        song = song,
                        onClick = { onPlay(shelf.songs, shelf.songs.indexOf(song)) },
                        onFavorite = { onFavorite(song) },
                        showTrackNumber = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactSongShelf(
    songs: List<Song>,
    viewModel: MusicViewModel,
    onPlay: (List<Song>, Int) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(songs, key = { it.id }) { song ->
            ElevatedCard(
                onClick = { onPlay(songs, songs.indexOf(song)) },
                modifier = Modifier.size(width = 276.dp, height = 86.dp),
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CoverArt(song.displayCoverArtId(), song.title, viewModel, Modifier.size(66.dp), fallbackSeed = song.id)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            song.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.Medium,
                        )
                        song.displayArtistName()?.let {
                            Text(
                                it,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        song.displayAlbumName()?.let {
                            Text(
                                it,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            ClearTuneTopAppBar(
                title = title,
                onBack = onBack,
                actions = actions,
            )
        },
        bottomBar = bottomBar,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
            content = content,
        )
    }
}

@Composable
private fun DetailHeader(
    title: String,
    subtitle: String,
    coverArtId: String?,
    viewModel: MusicViewModel,
    fallbackSeed: String = title,
    favorite: Boolean = false,
    onFavorite: (() -> Unit)? = null,
    onEditTitle: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CoverArt(
                id = coverArtId,
                description = title,
                viewModel = viewModel,
                modifier = Modifier.size(220.dp),
                fallbackSeed = fallbackSeed,
                requestSize = 768,
            )
        }
        Spacer(Modifier.height(18.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            onEditTitle?.let {
                FilledTonalIconButton(onClick = it) {
                    Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.rename_playlist))
                }
            }
            onFavorite?.let {
                FilledTonalIconButton(onClick = it) {
                    Icon(
                        if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = stringResource(
                            if (favorite) R.string.unfavorite_action else R.string.favorite_action,
                        ),
                        tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DetailActions(onPlay: () -> Unit, onDownload: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(onClick = onPlay, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.play_all)) }
        OutlinedButton(onClick = onDownload, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.download_action))
        }
    }
}

@Composable
private fun PlaylistDetailActions(
    hasSongs: Boolean,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onAdd: () -> Unit,
    moreExpanded: Boolean,
    onMoreExpandedChange: (Boolean) -> Unit,
    onManage: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Button(
            onClick = onPlay,
            enabled = hasSongs,
            modifier = Modifier
                .weight(1f)
                .height(52.dp),
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.play_all), maxLines = 1)
        }
        PlaylistCompactAction(
            icon = Icons.Rounded.Download,
            label = stringResource(R.string.download_action),
            enabled = hasSongs,
            onClick = onDownload,
        )
        PlaylistCompactAction(
            icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
            label = stringResource(R.string.add_short),
            onClick = onAdd,
        )
        Box {
            PlaylistCompactAction(
                icon = Icons.Rounded.MoreVert,
                label = stringResource(R.string.more_short),
                onClick = { onMoreExpandedChange(true) },
            )
            DropdownMenu(
                expanded = moreExpanded,
                onDismissRequest = { onMoreExpandedChange(false) },
            ) {
                if (hasSongs) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.manage_playlist_songs)) },
                        leadingIcon = { Icon(Icons.Rounded.CheckCircle, contentDescription = null) },
                        onClick = onManage,
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.rename_playlist)) },
                    leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                    onClick = onRename,
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.delete_playlist),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = onDelete,
                )
            }
        }
    }
}

@Composable
private fun PlaylistCompactAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.width(56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FilledTonalIconButton(onClick = onClick, enabled = enabled) {
            Icon(icon, contentDescription = label)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            },
            maxLines = 1,
        )
    }
}

@Composable
private fun PlaylistSelectionBar(
    allSelected: Boolean,
    removeEnabled: Boolean,
    onSelectAll: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onSelectAll) {
                Text(stringResource(if (allSelected) R.string.clear_selection else R.string.select_all))
            }
            FilledTonalButton(
                onClick = onRemove,
                enabled = removeEnabled,
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.remove_from_playlist))
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 12.dp),
    )
}

@Composable
private fun HomeSectionHeader(title: String, subtitle: String? = null) {
    Column(
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 30.dp, bottom = 12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AlbumShelf(
    albums: List<Album>,
    viewModel: MusicViewModel,
    onAlbum: (String) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(albums, key = { it.id }) { album ->
            Column(
                modifier = Modifier
                    .width(150.dp)
                    .clickable { onAlbum(album.id) },
            ) {
                CoverArt(
                    album.coverArtId,
                    album.name,
                    viewModel,
                    Modifier.size(150.dp),
                    fallbackSeed = album.id,
                    requestSize = 512,
                )
                Spacer(Modifier.height(9.dp))
                Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(
                    album.artistName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PlaylistShelf(
    playlists: List<Playlist>,
    viewModel: MusicViewModel,
    onPlaylist: (String) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (playlists.isEmpty()) {
            item("empty-playlists") {
                ElevatedCard(modifier = Modifier.size(width = 158.dp, height = 196.dp)) {
                    Column(Modifier.padding(8.dp)) {
                        PlaylistCover(null, stringResource(R.string.playlists), viewModel, Modifier.size(142.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.empty_playlists),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            items(playlists, key = { it.id }) { playlist ->
                ElevatedCard(
                    onClick = { onPlaylist(playlist.id) },
                    modifier = Modifier.size(width = 158.dp, height = 210.dp),
                ) {
                    Column(Modifier.padding(8.dp)) {
                        PlaylistCover(playlist.coverArtId, playlist.name, viewModel, Modifier.size(142.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(
                            playlist.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            stringResource(R.string.song_count, playlist.songCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumListRow(album: Album, viewModel: MusicViewModel, onClick: (String) -> Unit) {
    MediaRow(
        title = album.name,
        subtitle = album.artistName,
        coverArtId = album.coverArtId,
        viewModel = viewModel,
        onClick = { onClick(album.id) },
    )
}

@Composable
private fun AlbumGridCard(
    album: Album,
    viewModel: MusicViewModel,
    onClick: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick(album.id) },
    ) {
        CoverArt(
            id = album.coverArtId,
            description = album.name,
            viewModel = viewModel,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            fallbackSeed = album.id,
            requestSize = 512,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            album.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            album.artistName,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ArtistRow(artist: Artist, viewModel: MusicViewModel, onClick: (String) -> Unit) {
    MediaRow(
        title = artist.name,
        subtitle = stringResource(R.string.album_count, artist.albumCount),
        coverArtId = artist.coverArtId,
        viewModel = viewModel,
        onClick = { onClick(artist.id) },
    )
}

@Composable
private fun PlaylistRow(playlist: Playlist, viewModel: MusicViewModel, onClick: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick(playlist.id) }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlaylistCover(playlist.coverArtId, playlist.name, viewModel, Modifier.size(52.dp))
        Spacer(Modifier.size(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(playlist.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.song_count, playlist.songCount),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null)
    }
    HorizontalDivider(modifier = Modifier.padding(start = 86.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongRow(
    song: Song,
    onClick: () -> Unit,
    onFavorite: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    trailingText: String? = null,
    onTrailing: (() -> Unit)? = null,
    showTrackNumber: Boolean = true,
    showFileType: Boolean = false,
    showDuration: Boolean = true,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
) {
    val fileType = song.suffix
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showTrackNumber) {
            Text(
                text = song.trackNumber?.toString() ?: "♪",
                modifier = Modifier.size(32.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                song.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
            )
            val subtitle = listOfNotNull(song.displayArtistName(), song.displayAlbumName()).joinToString(" · ")
            if (subtitle.isNotEmpty() || showFileType && !fileType.isNullOrBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                if (subtitle.isNotEmpty()) Text(
                    subtitle,
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (showFileType && !fileType.isNullOrBlank()) {
                    SongFormatBadge(fileType)
                }
            }
        }
        if (showDuration) {
            Text(formatDuration(song.durationSeconds), style = MaterialTheme.typography.bodySmall)
        }
        onFavorite?.let {
            val isLiked = song.starredAt != null
            IconButton(onClick = it) {
                Icon(
                    imageVector = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = stringResource(if (isLiked) R.string.unlike_song else R.string.like_song),
                    tint = if (isLiked) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        if (trailingText != null && onTrailing != null) {
            TextButton(onClick = onTrailing) { Text(trailingText) }
        }
        trailingContent?.invoke(this)
    }
    HorizontalDivider(modifier = Modifier.padding(start = if (showTrackNumber) 52.dp else 20.dp))
}

@Composable
private fun SongFormatBadge(format: String) {
    Surface(
        modifier = Modifier.padding(start = 6.dp, end = 5.dp),
        shape = RoundedCornerShape(5.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = format.uppercase(),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 10.sp),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun LibrarySongActions(
    song: Song,
    playlists: List<Playlist>,
    viewModel: MusicViewModel,
    onAddToPlaylist: (String) -> Unit,
    onToggleLike: () -> Unit,
    onPlayNext: () -> Unit,
    onDownload: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var showPlaylistPicker by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    val isLiked = song.starredAt != null

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.more_actions))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.add_to_playlist)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null) },
                onClick = {
                    expanded = false
                    showPlaylistPicker = true
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(if (isLiked) R.string.unlike_song else R.string.like_song)) },
                leadingIcon = {
                    Icon(
                        if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = null,
                    )
                },
                onClick = {
                    expanded = false
                    onToggleLike()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.play_next)) },
                leadingIcon = { Icon(Icons.Rounded.SkipNext, contentDescription = null) },
                onClick = {
                    expanded = false
                    onPlayNext()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.download_action)) },
                leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = null) },
                onClick = {
                    expanded = false
                    onDownload()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.song_details)) },
                leadingIcon = { Icon(Icons.Rounded.Info, contentDescription = null) },
                onClick = {
                    expanded = false
                    showDetails = true
                },
            )
        }
    }

    if (showPlaylistPicker) {
        PlaylistPickerDialog(
            playlists = playlists,
            viewModel = viewModel,
            onSelect = { playlistId ->
                onAddToPlaylist(playlistId)
                showPlaylistPicker = false
            },
            onDismiss = { showPlaylistPicker = false },
        )
    }

    if (showDetails) {
        SongDetailsDialog(song = song, onDismiss = { showDetails = false })
    }
}

@Composable
private fun AddToPlaylistAction(
    playlists: List<Playlist>,
    viewModel: MusicViewModel,
    onAddToPlaylist: (String) -> Unit,
) {
    var showPlaylistPicker by remember { mutableStateOf(false) }
    IconButton(onClick = { showPlaylistPicker = true }) {
        Icon(
            Icons.AutoMirrored.Rounded.PlaylistAdd,
            contentDescription = stringResource(R.string.add_to_playlist),
        )
    }
    if (showPlaylistPicker) {
        PlaylistPickerDialog(
            playlists = playlists,
            viewModel = viewModel,
            onSelect = { playlistId ->
                onAddToPlaylist(playlistId)
                showPlaylistPicker = false
            },
            onDismiss = { showPlaylistPicker = false },
        )
    }
}

@Composable
private fun PlaylistPickerDialog(
    playlists: List<Playlist>,
    viewModel: MusicViewModel,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_to_playlist)) },
        text = {
            if (playlists.isEmpty()) {
                Text(stringResource(R.string.empty_playlists))
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(playlists, key = Playlist::id) { playlist ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(playlist.id) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PlaylistCover(
                                id = playlist.coverArtId,
                                description = playlist.name,
                                viewModel = viewModel,
                                modifier = Modifier.size(40.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(playlist.name, fontWeight = FontWeight.Medium)
                                Text(
                                    stringResource(R.string.song_count, playlist.songCount),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun SongDetailsDialog(song: Song, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.song_details)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(song.title, style = MaterialTheme.typography.titleMedium)
                song.displayArtistName()?.let { Text(stringResource(R.string.song_detail_artist, it)) }
                song.displayAlbumName()?.let { Text(stringResource(R.string.song_detail_album, it)) }
                Text(stringResource(R.string.song_detail_duration, formatDuration(song.durationSeconds)))
                Text(
                    stringResource(
                        R.string.song_detail_format,
                        listOfNotNull(
                            song.suffix?.uppercase(),
                            song.bitRate?.let { "$it kbps" },
                        ).joinToString(" · ").ifBlank { stringResource(R.string.unknown_format) },
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close_action)) }
        },
    )
}

@Composable
private fun MediaRow(
    title: String,
    subtitle: String,
    coverArtId: String?,
    viewModel: MusicViewModel,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(coverArtId, title, viewModel, Modifier.size(56.dp))
        Spacer(Modifier.size(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider(modifier = Modifier.padding(start = 90.dp))
}

@Composable
internal fun CoverArt(
    id: String?,
    description: String,
    viewModel: MusicViewModel,
    modifier: Modifier = Modifier,
    fallbackSeed: String = description,
    requestSize: Int = 192,
) {
    val displayableId = id.displayableArtworkId()
    val url by produceState<String?>(initialValue = null, displayableId, requestSize) {
        value = displayableId?.let { viewModel.coverArtUrl(it, requestSize) }
    }
    val context = LocalPlatformContext.current
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .semantics { contentDescription = description },
    ) {
        val fallbackResource = remember(fallbackSeed) { clearTuneFallbackCover(fallbackSeed) }
        val fallbackPainter = painterResource(fallbackResource)
        AsyncImage(
            model = url?.let {
                ImageRequest.Builder(context)
                    .data(it)
                    .size(requestSize)
                    .memoryCacheKey("cover-$displayableId-$requestSize")
                    .diskCacheKey("cover-$displayableId-$requestSize")
                    .crossfade(false)
                    .build()
            },
            placeholder = fallbackPainter,
            error = fallbackPainter,
            fallback = fallbackPainter,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            filterQuality = if (requestSize <= 384) FilterQuality.Low else FilterQuality.Medium,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun PlaylistCover(
    id: String?,
    description: String,
    viewModel: MusicViewModel,
    modifier: Modifier = Modifier,
) {
    if (id != null) {
        CoverArt(
            id = id,
            description = description,
            viewModel = viewModel,
            modifier = modifier,
        )
    } else {
        Box(
            modifier = modifier
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .semantics { contentDescription = description },
        ) {
            ClearTuneArtworkPlaceholder(
                icon = Icons.AutoMirrored.Rounded.QueueMusic,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private val clearTuneFallbackCovers = intArrayOf(
    R.drawable.cleartune_cover_horizon,
    R.drawable.cleartune_cover_orb,
    R.drawable.cleartune_cover_ribbon,
    R.drawable.cleartune_cover_portal,
)

internal fun clearTuneFallbackCover(seed: String): Int =
    clearTuneFallbackCovers[(seed.hashCode() and Int.MAX_VALUE) % clearTuneFallbackCovers.size]

private fun String.knownMetadataOrNull(): String? = trim().takeUnless { value ->
    value.isBlank() ||
        value.contains("unknown", ignoreCase = true) ||
        value.contains("未知", ignoreCase = true) ||
        value.equals("n/a", ignoreCase = true) ||
        value.equals("null", ignoreCase = true)
}

internal fun Song.displayArtistName(): String? = artistName.knownMetadataOrNull()

internal fun Song.displayAlbumName(): String? = albumName.knownMetadataOrNull()

internal fun Song.displayCoverArtId(): String? {
    val hasUnknownMetadata = displayAlbumName() == null || displayArtistName() == null
    return coverArtId.takeUnless { hasUnknownMetadata }.displayableArtworkId()
}

@Composable
private fun LoadingBlock(text: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(text ?: stringResource(R.string.loading_library))
    }
}

@Composable
private fun EmptyBlock(
    text: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onAction) { Text(action) }
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val minutes = seconds / 60
    val remaining = seconds % 60
    return "%d:%02d".format(minutes, remaining)
}
