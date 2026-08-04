package com.cleartune.app

import android.animation.ValueAnimator
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.cleartune.core.designsystem.theme.ClearTuneDimensions
import com.cleartune.core.designsystem.theme.ClearTuneTheme
import com.cleartune.core.model.ThemeMode
import com.cleartune.core.model.PlaylistId
import com.cleartune.core.model.QueueCommand
import com.cleartune.core.model.QueueItemId
import com.cleartune.core.contracts.QueueRepository
import com.cleartune.core.model.DownloadCommand
import com.cleartune.core.model.DownloadState
import com.cleartune.core.model.SongQuery
import com.cleartune.feature.downloads.DownloadsFeatureDependencies
import com.cleartune.feature.downloads.DownloadsFeatureEntry
import com.cleartune.feature.library.LibraryFeatureDependencies
import com.cleartune.feature.library.LibraryFeatureEntry
import com.cleartune.feature.player.MiniPlayer
import com.cleartune.feature.player.PlayerFeatureDependencies
import com.cleartune.feature.player.PlayerFeatureEntry
import com.cleartune.feature.player.PlayerTrackActionState
import com.cleartune.feature.playlists.PlaylistsFeatureDependencies
import com.cleartune.feature.playlists.PlaylistsFeatureEntry
import com.cleartune.feature.settings.SettingsFeatureDependencies
import com.cleartune.feature.settings.SettingsFeatureEntry
import com.cleartune.feature.settings.isReducedMotionEnabled
import com.cleartune.feature.sources.SourcesFeatureDependencies
import com.cleartune.feature.sources.SourcesFeatureEntry
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import com.cleartune.playback.PlaybackQueueStateWriter

object AppRoutes {
    const val Library = "library"
    const val LibraryDetail = "library/detail"
    const val Player = "player"
    const val Playlists = "playlists"
    const val PlaylistDetail = "playlists/{playlistId}"
    const val Sources = "sources"
    const val Downloads = "downloads"
    const val Settings = "settings"
    val all = listOf(Library, LibraryDetail, Player, Playlists, Sources, Downloads, Settings)
    val restorable = all

    fun playlistDetail(playlistId: String): String =
        "$Playlists/${URLEncoder.encode(playlistId, StandardCharsets.UTF_8.name()).replace("+", "%20")}"

    fun playlistId(route: String): String? = route.removePrefix("$Playlists/")
        .takeIf { route.startsWith("$Playlists/") && it.isNotBlank() }
        ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }

    fun restore(route: String?): String = when {
        route in restorable -> route!!
        route?.let(::playlistId) != null -> route
        else -> Library
    }
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
fun ClearTuneApp(
    container: AppContainer,
    startDestination: String = AppRoutes.Library,
) {
    val settings by container.settingsRepository.settings.collectAsState(initial = com.cleartune.core.model.AppSettings())
    val darkTheme = when (settings.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    val reducedMotion = isReducedMotionEnabled(
        settings.reducedMotionMode,
        systemAnimationsEnabled = ValueAnimator.areAnimatorsEnabled(),
    )
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
                    composable(AppRoutes.Library) {
                        LibraryHomeScreen { navController.navigate(it) }
                    }
                    composable(AppRoutes.LibraryDetail) {
                        LibraryFeatureEntry.Content(
                            dependencies = LibraryFeatureDependencies(
                                container.libraryRepository,
                                container.playbackGateway,
                                container.playlistRepository,
                            ),
                            onNavigate = navController::navigateOrBack,
                        )
                    }
                    composable(AppRoutes.Player) {
                        PlayerFeatureEntry.Content(
                            PlayerFeatureDependencies(
                                container.playbackGateway,
                                container.queueRepository,
                                container.playbackGateway::syncQueue,
                                queueTitles = container.libraryRepository.observeSongs(SongQuery()).map { tracks ->
                                    tracks.associate { it.id to it.title }
                                },
                                observeTrackActions = { trackId ->
                                    container.downloadRepository.observeDownloads().map { downloads ->
                                        PlayerTrackActionState(
                                            isDownloaded = downloads.any {
                                                it.trackId == trackId && it.state == DownloadState.COMPLETED
                                            },
                                            canDownload = container.downloadCommandsAvailable,
                                            downloadUnavailableReason = "Downloads require a configured download adapter",
                                        )
                                    }
                                },
                                onToggleDownload = { trackId ->
                                    val existing = container.downloadRepository.observeDownloads().first()
                                        .firstOrNull { it.trackId == trackId }
                                    container.downloadRepository.dispatch(
                                        if (existing == null) DownloadCommand.Enqueue(trackId)
                                        else DownloadCommand.Delete(existing.id),
                                    )
                                },
                                onPlayOccurrence = { occurrenceId ->
                                    playQueueOccurrence(
                                        container.queueRepository,
                                        container.queueRepository,
                                        occurrenceId,
                                        container.playbackGateway::syncQueue,
                                    )
                                    container.playbackGateway.dispatch(com.cleartune.core.model.PlaybackCommand.Play)
                                },
                                onRetry = { trackId ->
                                    container.playbackGateway.dispatch(
                                        com.cleartune.core.model.PlaybackCommand.PlayTrack(trackId),
                                    )
                                },
                            ),
                            navController::navigateOrBack,
                        )
                    }
                    composable(AppRoutes.Playlists) {
                        PlaylistsFeatureEntry.Content(
                            PlaylistsFeatureDependencies(
                                container.playlistRepository,
                                container.playbackGateway,
                                container.queueRepository,
                                container.playlistDetailsProvider,
                                container.libraryRepository.observeSongs(SongQuery()).map { tracks ->
                                    tracks.associate { it.id to it.title }
                                },
                                container.playbackGateway::syncQueue,
                            ),
                            navController::navigateOrBack,
                        )
                    }
                    composable(
                        route = AppRoutes.PlaylistDetail,
                        arguments = listOf(navArgument("playlistId") { type = NavType.StringType }),
                    ) { entry ->
                        val playlistId = entry.arguments?.getString("playlistId")?.let(::PlaylistId)
                        PlaylistsFeatureEntry.Content(
                            PlaylistsFeatureDependencies(
                                container.playlistRepository,
                                container.playbackGateway,
                                container.queueRepository,
                                container.playlistDetailsProvider,
                                container.libraryRepository.observeSongs(SongQuery()).map { tracks ->
                                    tracks.associate { it.id to it.title }
                                },
                                container.playbackGateway::syncQueue,
                            ),
                            navController::navigateOrBack,
                            playlistId,
                        )
                    }
                    composable(AppRoutes.Sources) {
                        SourcesFeatureEntry.Content(
                            SourcesFeatureDependencies(container.sourceRepository),
                            navController::navigateOrBack,
                        )
                    }
                    composable(AppRoutes.Downloads) {
                        DownloadsFeatureEntry.Content(
                            DownloadsFeatureDependencies(container.downloadRepository, container.playbackGateway),
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
                    dependencies = PlayerFeatureDependencies(
                        container.playbackGateway,
                        container.queueRepository,
                        container.playbackGateway::syncQueue,
                    ),
                    onOpenPlayer = { navController.navigate(AppRoutes.Player) },
                )
            }
        }
    }
}

@Composable
private fun LibraryHomeScreen(onNavigate: (String) -> Unit) {
    val categories = listOf(
        LibraryCategory("歌曲", "按标题、艺术家或专辑浏览", AppRoutes.LibraryDetail),
        LibraryCategory("专辑", "查看完整唱片收藏", AppRoutes.LibraryDetail),
        LibraryCategory("艺术家", "使用简洁默认头像展示", AppRoutes.LibraryDetail),
        LibraryCategory("歌单", "创建和整理自己的播放列表", AppRoutes.Playlists),
        LibraryCategory("已下载", "离线也能继续听", AppRoutes.Downloads),
        LibraryCategory("音乐来源", "本地存储与 WebDAV", AppRoutes.Sources),
    )
    Column(Modifier.fillMaxSize().padding(horizontal = ClearTuneDimensions.spacingMd)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = ClearTuneDimensions.spacingSm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("资料库", style = MaterialTheme.typography.headlineLarge)
                Text("你的全部音乐", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { onNavigate(AppRoutes.LibraryDetail) }) { Text("搜索") }
            TextButton(
                onClick = { onNavigate(AppRoutes.Settings) },
                modifier = Modifier.semantics { contentDescription = "Open settings" },
            ) { Text("Settings") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(ClearTuneDimensions.spacingSm)) {
            items(categories) { category ->
                Card(
                    Modifier.fillMaxWidth().clickable(onClickLabel = "Open ${category.route}") {
                        onNavigate(category.route)
                    },
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(ClearTuneDimensions.spacingMd),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(category.title, style = MaterialTheme.typography.titleLarge)
                            Text(category.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("›", style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
        }
    }
}

private data class LibraryCategory(val title: String, val subtitle: String, val route: String)

private fun NavHostController.navigateOrBack(route: String) {
    if (route == "back") popBackStack() else navigate(route)
}
