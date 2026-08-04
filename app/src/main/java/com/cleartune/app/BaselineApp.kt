package com.cleartune.app

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
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.cleartune.core.designsystem.theme.ClearTuneDimensions
import com.cleartune.core.designsystem.theme.ClearTuneTheme
import com.cleartune.core.model.ThemeMode
import com.cleartune.feature.downloads.DownloadsFeatureDependencies
import com.cleartune.feature.downloads.DownloadsFeatureEntry
import com.cleartune.feature.library.LibraryFeatureDependencies
import com.cleartune.feature.library.LibraryFeatureEntry
import com.cleartune.feature.player.MiniPlayer
import com.cleartune.feature.player.PlayerFeatureDependencies
import com.cleartune.feature.player.PlayerFeatureEntry
import com.cleartune.feature.playlists.PlaylistsFeatureDependencies
import com.cleartune.feature.playlists.PlaylistsFeatureEntry
import com.cleartune.feature.settings.SettingsFeatureDependencies
import com.cleartune.feature.settings.SettingsFeatureEntry
import com.cleartune.feature.sources.SourcesFeatureDependencies
import com.cleartune.feature.sources.SourcesFeatureEntry

object AppRoutes {
    const val Library = "library"
    const val LibraryDetail = "library/detail"
    const val Player = "player"
    const val Playlists = "playlists"
    const val Sources = "sources"
    const val Downloads = "downloads"
    const val Settings = "settings"
    val all = listOf(Library, LibraryDetail, Player, Playlists, Sources, Downloads, Settings)
}

@Composable
fun ClearTuneApp(container: AppContainer) {
    val settings by container.settingsRepository.settings.collectAsState(initial = com.cleartune.core.model.AppSettings())
    val darkTheme = when (settings.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    ClearTuneTheme(darkTheme = darkTheme) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
            val navController = rememberNavController()
            Column(Modifier.fillMaxSize()) {
                NavHost(
                    navController = navController,
                    startDestination = AppRoutes.Library,
                    modifier = Modifier.weight(1f),
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
                            ),
                            navController::navigateOrBack,
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
            TextButton(onClick = { onNavigate(AppRoutes.Settings) }) { Text("设置") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(ClearTuneDimensions.spacingSm)) {
            items(categories) { category ->
                Card(
                    Modifier.fillMaxWidth().clickable { onNavigate(category.route) },
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
