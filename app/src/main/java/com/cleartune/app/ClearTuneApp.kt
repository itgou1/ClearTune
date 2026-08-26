package com.cleartune.app

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
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
import com.cleartune.app.library.DetailUiState
import com.cleartune.app.library.LibraryUiState
import com.cleartune.app.library.FolderUiState
import com.cleartune.app.library.MusicViewModel
import com.cleartune.app.library.SearchUiState
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

private data class MainDestination(
    val route: String,
    val label: Int,
    val icon: ImageVector,
)

private val mainDestinations = listOf(
    MainDestination("home", R.string.nav_home, Icons.Rounded.Home),
    MainDestination("library", R.string.nav_library, Icons.Rounded.LibraryMusic),
    MainDestination("my", R.string.nav_my, Icons.Rounded.Person),
)

@Composable
fun ClearTuneApp(
    profile: ServerProfile,
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
    val snackbarHostState = remember { SnackbarHostState() }
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBottomBar = currentRoute in mainDestinations.map { it.route }
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
    LaunchedEffect(playerState.currentSong?.id) {
        viewModel.updateRecommendationExclusions(setOfNotNull(playerState.currentSong?.id))
        playerState.currentSong?.let(viewModel::loadLyrics)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (showBottomBar) {
                Column {
                    if (playerState.currentSong != null) {
                        MiniPlayer(
                            state = playerState,
                            musicViewModel = viewModel,
                            onOpen = { navController.navigate("now-playing") },
                            onToggle = playerViewModel::togglePlayPause,
                            onQueue = { navController.navigate("queue") },
                        )
                    }
                    NavigationBar {
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
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier
                .padding(padding)
                .then(if (showBottomBar) Modifier.statusBarsPadding() else Modifier),
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
                    onShuffleAll = {
                        val shuffled = libraryState.songs.shuffled()
                        if (shuffled.isNotEmpty()) playerViewModel.play(shuffled, 0)
                    },
                )
            }
            composable("search") {
                SearchScreen(
                    state = searchState,
                    recentSearches = recentSearches,
                    viewModel = viewModel,
                    onBack = navController::popBackStack,
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
                    onSearch = { navController.navigate("search") },
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
            composable("offline") {
                OfflineMusicScreen(
                    downloads = downloads,
                    songs = libraryState.songs,
                    onBack = navController::popBackStack,
                    onPlay = playerViewModel::play,
                )
            }
            composable(
                route = "album/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                LaunchedEffect(id) { viewModel.loadAlbum(id) }
                AlbumDetailScreen(
                    state = detailState,
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
                PlaylistDetailScreen(
                    state = detailState,
                    viewModel = viewModel,
                    onBack = navController::popBackStack,
                    onPlay = playerViewModel::play,
                    onDownload = { downloadViewModel.download(it) },
                    allSongs = libraryState.songs,
                    onRename = viewModel::renamePlaylist,
                    onAddSong = viewModel::addPlaylistSong,
                    onRemoveSong = viewModel::removePlaylistSong,
                    onDelete = viewModel::deletePlaylist,
                )
            }
            composable("settings") {
                FullSettingsScreen(
                    profile = profile,
                    viewModel = settingsViewModel,
                    onBack = navController::popBackStack,
                    onLogout = onLogout,
                )
            }
            composable("discovery") {
                DiscoveryScreen(
                    shelves = recommendations,
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
                    onQueue = { navController.navigate("queue") },
                    onEqualizer = { navController.navigate("equalizer") },
                    onFavorite = { song, favorite -> viewModel.setSongFavorite(song, favorite) },
                    onDownload = { song -> downloadViewModel.download(listOf(song)) },
                )
            }
            composable("queue") {
                QueueScreen(
                    state = playerState,
                    playerViewModel = playerViewModel,
                    onBack = navController::popBackStack,
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
    onShuffleAll: () -> Unit,
) {
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
                Column {
                    Text(
                        text = stringResource(R.string.home_greeting, profile.username),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        text = profile.serverType,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            DiscoveryHeroCard(
                songs = heroSongs,
                viewModel = viewModel,
                shuffleEnabled = state.songs.isNotEmpty(),
                onDiscovery = onDiscovery,
                onShuffleAll = onShuffleAll,
            )
        }
        if (state.isRefreshing && state.albums.isEmpty()) {
            item { LoadingBlock() }
        } else if (state.albums.isEmpty() && state.songs.isEmpty()) {
            item {
                EmptyBlock(
                    text = state.errorMessage ?: stringResource(R.string.empty_library),
                    action = stringResource(R.string.refresh),
                    onAction = onRefresh,
                )
            }
        } else {
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
                    onDiscovery = onDiscovery,
                    onRefresh = onRefreshRecommendations,
                    onPlay = onPlay,
                )
            }
        }
    }
}

@Composable
private fun DiscoveryHeroCard(
    songs: List<Song>,
    viewModel: MusicViewModel,
    shuffleEnabled: Boolean,
    onDiscovery: () -> Unit,
    onShuffleAll: () -> Unit,
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
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Surface(
                    modifier = Modifier.size(42.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.Explore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
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
            }
            Column(horizontalAlignment = Alignment.End) {
                RecommendationCoverMosaic(songs = songs, viewModel = viewModel)
                Spacer(Modifier.height(8.dp))
                FilledIconButton(
                    onClick = onShuffleAll,
                    enabled = shuffleEnabled,
                ) {
                    Icon(
                        Icons.Rounded.Shuffle,
                        contentDescription = stringResource(R.string.shuffle_all),
                    )
                }
            }
        }
    }
}

@Composable
private fun RecommendationSceneCards(
    newTaste: RecommendationShelf?,
    frequent: RecommendationShelf?,
    viewModel: MusicViewModel,
    onDiscovery: () -> Unit,
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
            actionIcon = Icons.Rounded.Refresh,
            actionDescription = stringResource(R.string.change_batch),
            actionEnabled = true,
            onAction = onRefresh,
            onClick = onDiscovery,
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
            onClick = onDiscovery,
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
    onClick: () -> Unit,
) {
    ElevatedCard(
        onClick = onClick,
        modifier = modifier.height(228.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
        ) {
            RecommendationCoverPair(songs = songs, viewModel = viewModel)
            Spacer(Modifier.height(14.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                description,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
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
        CoverArt(song.coverArtId, song.title, viewModel, modifier)
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
            ClearTuneGradientHeader(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                Text(stringResource(R.string.nav_my), style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.AccountCircle,
                        contentDescription = null,
                        modifier = Modifier.size(68.dp),
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
                                maxLines = 1,
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
        item { MySectionTitle(stringResource(R.string.my_music)) }
        item {
            MyEntry(
                icon = Icons.Rounded.Favorite,
                title = stringResource(R.string.favorites),
                subtitle = stringResource(R.string.liked_song_count, likedCount),
                onClick = onFavorites,
            )
        }
        item { MySectionTitle(stringResource(R.string.playlists)) }
        items(playlists, key = Playlist::id) { playlist ->
            PlaylistRow(playlist = playlist, viewModel = viewModel, onClick = onPlaylist)
        }
        item(key = "create-playlist") {
            NewPlaylistRow(onClick = { showCreatePlaylist = true })
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
    viewModel: MusicViewModel,
    onBack: () -> Unit,
    onAlbum: (String) -> Unit,
    onArtist: (String) -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
    onPlaylist: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(16.dp),
    ) {
        item {
            ClearTuneGradientHeader(
                modifier = Modifier.padding(bottom = 12.dp),
                contentPadding = PaddingValues(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(text = stringResource(R.string.nav_search), style = MaterialTheme.typography.headlineSmall)
                }
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::updateSearchQuery,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.search_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { viewModel.submitSearch() }),
                )
            }
        }
        when {
            state.query.isBlank() && recentSearches.isEmpty() -> item {
                EmptyBlock(stringResource(R.string.search_start))
            }
            state.query.isBlank() -> {
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
                            .clickable { viewModel.updateSearchQuery(query) },
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
            state.isSearching -> item { LoadingBlock(stringResource(R.string.searching)) }
            state.errorMessage != null -> item { EmptyBlock(state.errorMessage) }
            state.results.artists.isEmpty() && state.results.albums.isEmpty() &&
                state.results.songs.isEmpty() && state.results.playlists.isEmpty() -> {
                item { EmptyBlock(stringResource(R.string.no_results)) }
            }
            else -> {
                if (state.results.artists.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.artists)) }
                    items(state.results.artists, key = { "artist-${it.id}" }) {
                        ArtistRow(it, viewModel, onArtist)
                    }
                }
                if (state.results.albums.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.albums)) }
                    items(state.results.albums, key = { "album-${it.id}" }) {
                        AlbumListRow(it, viewModel, onAlbum)
                    }
                }
                if (state.results.songs.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.songs)) }
                    items(state.results.songs, key = { "song-${it.id}" }) { song ->
                        SongRow(
                            song = song,
                            onClick = { onPlay(state.results.songs, state.results.songs.indexOf(song)) },
                            onFavorite = { viewModel.toggleSongFavorite(song) },
                        )
                    }
                }
                if (state.results.playlists.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.playlists)) }
                    items(state.results.playlists, key = { "playlist-${it.id}" }) { playlist ->
                        Text(
                            playlist.name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPlaylist(playlist.id) }
                                .padding(horizontal = 12.dp, vertical = 14.dp),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
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
    onSearch: () -> Unit,
) {
    val labels = listOf(
        stringResource(R.string.albums),
        stringResource(R.string.artists),
        stringResource(R.string.songs),
        stringResource(R.string.genres),
        stringResource(R.string.folders),
    )
    var selectedTab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        ClearTuneGradientHeader(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(stringResource(R.string.nav_library), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        stringResource(R.string.library_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row {
                    FilledTonalIconButton(onClick = onRefresh) {
                        Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                    FilledTonalIconButton(onClick = onSearch) {
                        Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.nav_search))
                    }
                }
            }
        }
        PrimaryScrollableTabRow(selectedTabIndex = selectedTab) {
            labels.forEachIndexed { index, label ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(label) },
                )
            }
        }
        if (state.isRefreshing && state.albums.isEmpty()) {
            LoadingBlock()
        } else if (state.albums.isEmpty() && state.artists.isEmpty() && state.songs.isEmpty()) {
            EmptyBlock(
                text = state.errorMessage ?: stringResource(R.string.empty_library),
                action = stringResource(R.string.refresh),
                onAction = onRefresh,
            )
        } else {
            when (selectedTab) {
                0 -> LazyColumn { items(state.albums, key = { it.id }) { AlbumListRow(it, viewModel, onAlbum) } }
                1 -> LazyColumn { items(state.artists, key = { it.id }) { ArtistRow(it, viewModel, onArtist) } }
                2 -> LazyColumn {
                    items(state.songs, key = { it.id }) { song ->
                        SongRow(song, { onPlay(state.songs, state.songs.indexOf(song)) }) {
                            viewModel.toggleSongFavorite(song)
                        }
                    }
                }
                3 -> LazyColumn {
                    items(genres) { genre ->
                        Text(
                            text = genre,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 16.dp),
                        )
                        HorizontalDivider()
                    }
                }
                else -> FolderBrowser(
                    state = folderState,
                    viewModel = viewModel,
                    onPlay = onPlay,
                )
            }
        }
    }
}

@Composable
private fun FolderBrowser(
    state: FolderUiState,
    viewModel: MusicViewModel,
    onPlay: (List<Song>, Int) -> Unit,
) {
    LazyColumn {
        if (state.path.isNotEmpty()) {
            item {
                TextButton(onClick = viewModel::folderBack) {
                    Text(stringResource(R.string.folder_back_path, state.path.joinToString(" / ") { it.name }))
                }
            }
        }
        if (state.loading) item { LoadingBlock() }
        state.errorMessage?.let { item { EmptyBlock(it) } }
        val folders = if (state.path.isEmpty()) state.roots else state.folders
        items(folders, key = { "folder-${it.id}" }) { folder ->
            Text(
                text = "▣ ${folder.name}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.openFolder(folder) }
                    .padding(horizontal = 20.dp, vertical = 16.dp),
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
    viewModel: MusicViewModel,
    onBack: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
    onDownload: (List<Song>) -> Unit,
) {
    DetailScaffold(title = state.album?.name.orEmpty(), onBack = onBack) {
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
                SongRow(song, { onPlay(state.songs, state.songs.indexOf(song)) }) {
                    viewModel.toggleSongFavorite(song)
                }
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
    DetailScaffold(title = state.artist?.name.orEmpty(), onBack = onBack) {
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
    onRemoveSong: (String, Int) -> Unit,
    onDelete: (String) -> Unit,
) {
    var showRename by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var newName by remember(state.playlist?.id) { mutableStateOf(state.playlist?.name.orEmpty()) }
    DetailScaffold(
        title = state.playlist?.name.orEmpty(),
        onBack = onBack,
        actions = {
            if (state.playlist != null) {
                IconButton(onClick = { showDelete = true }) {
                    Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.delete_playlist))
                }
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
                        onEditTitle = { showRename = true },
                    )
                    DetailActions(
                        onPlay = { onPlay(state.songs, 0) },
                        onDownload = { onDownload(state.songs) },
                    )
                    FilledTonalButton(
                        onClick = { showAdd = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.add_songs))
                    }
                }
            }
            state.errorMessage?.let { message -> item { EmptyBlock(message) } }
            items(state.songs, key = { it.id }) { song ->
                val index = state.songs.indexOf(song)
                SongRow(
                    song = song,
                    onClick = { onPlay(state.songs, index) },
                    onFavorite = { viewModel.toggleSongFavorite(song) },
                    trailingText = stringResource(R.string.remove_action),
                    onTrailing = { state.playlist?.id?.let { onRemoveSong(it, index) } },
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
                                Text(song.artistName, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text(stringResource(R.string.close_action)) } },
        )
    }
    if (showDelete && playlist != null) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text(stringResource(R.string.delete_playlist_question)) },
            text = { Text(stringResource(R.string.delete_playlist_explanation)) },
            confirmButton = {
                TextButton(onClick = { onDelete(playlist.id); showDelete = false; onBack() }) {
                    Text(stringResource(R.string.delete_action))
                }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoveryScreen(
    shelves: List<RecommendationShelf>,
    onRefresh: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
    onFavorite: (Song) -> Unit,
    onBack: () -> Unit,
) {
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
        ) {
            if (shelves.isEmpty()) {
                item { EmptyBlock(stringResource(R.string.discovery_empty)) }
            }
            shelves.forEach { shelf ->
                item {
                    ClearTuneGradientHeader(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        contentPadding = PaddingValues(14.dp),
                    ) {
                        Text(shelf.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            shelf.reason,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                items(shelf.songs, key = { "${shelf.id}-${it.id}" }) { song ->
                    SongRow(
                        song,
                        onClick = { onPlay(shelf.songs, shelf.songs.indexOf(song)) },
                        onFavorite = { onFavorite(song) },
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
                    CoverArt(song.coverArtId, song.title, viewModel, Modifier.size(66.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            song.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            song.artistName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            song.albumName,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
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
    favorite: Boolean = false,
    onFavorite: (() -> Unit)? = null,
    onEditTitle: (() -> Unit)? = null,
) {
    ClearTuneGradientHeader(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentPadding = PaddingValues(20.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CoverArt(coverArtId, title, viewModel, Modifier.size(220.dp))
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
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
            }
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            onFavorite?.let {
                FilledTonalIconButton(onClick = it) {
                    Icon(
                        Icons.Rounded.Favorite,
                        contentDescription = stringResource(
                            if (favorite) R.string.unfavorite_action else R.string.favorite_action,
                        ),
                        tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
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
                CoverArt(album.coverArtId, album.name, viewModel, Modifier.size(150.dp))
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

@Composable
private fun SongRow(
    song: Song,
    onClick: () -> Unit,
    onFavorite: (() -> Unit)? = null,
    trailingText: String? = null,
    onTrailing: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = song.trackNumber?.toString() ?: "♪",
            modifier = Modifier.size(32.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(
                "${song.artistName} · ${song.albumName}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(formatDuration(song.durationSeconds), style = MaterialTheme.typography.bodySmall)
        onFavorite?.let {
            TextButton(onClick = it) {
                Text(stringResource(if (song.starredAt != null) R.string.favorited else R.string.favorite_action))
            }
        }
        if (trailingText != null && onTrailing != null) {
            TextButton(onClick = onTrailing) { Text(trailingText) }
        }
    }
    HorizontalDivider(modifier = Modifier.padding(start = 52.dp))
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
) {
    val url by produceState<String?>(initialValue = null, id) {
        value = id?.let { viewModel.coverArtUrl(it) }
    }
    val context = LocalPlatformContext.current
    if (url == null) {
        Box(
            modifier = modifier
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("♫", style = MaterialTheme.typography.headlineMedium)
        }
    } else {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(url)
                .memoryCacheKey("cover-$id")
                .diskCacheKey("cover-$id")
                .build(),
            contentDescription = description,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp)),
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
        CoverArt(id, description, viewModel, modifier)
    } else {
        Surface(
            modifier = modifier.aspectRatio(1f),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.AutoMirrored.Rounded.QueueMusic,
                    contentDescription = description,
                    modifier = Modifier.size(42.dp),
                )
            }
        }
    }
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
