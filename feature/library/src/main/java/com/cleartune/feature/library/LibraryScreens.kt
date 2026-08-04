package com.cleartune.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cleartune.core.designsystem.component.ArtistAvatar
import com.cleartune.core.model.Album
import com.cleartune.core.model.Artist
import com.cleartune.core.model.PlaybackCommand
import com.cleartune.core.model.SearchResults
import com.cleartune.core.model.SongQuery
import com.cleartune.core.model.SongSort
import com.cleartune.core.model.TrackSummary
import kotlinx.coroutines.launch

@Composable
fun LibraryHomeScreen(
    state: LibraryHomeUiState,
    onNavigate: (String) -> Unit,
    onRequestLocalAccess: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onAddWebDav: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "资料库",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onNavigate(LibraryRoutes.search) }) { Text("搜索") }
                TextButton(onClick = { onNavigate(LibraryRoutes.settings) }) { Text("设置") }
            }
            if (state.isSyncing) {
                state.syncProgress?.let { progress ->
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
            }
            state.inlineMessage?.let { message ->
                InlineNotice(message = message, action = "重试", onAction = onRefresh)
                Spacer(Modifier.height(8.dp))
            }
        }
        items(state.categories, key = LibraryCategoryUi::id) { category ->
            CategoryRow(category, onClick = { onNavigate(category.route) })
        }
        if (state.emptyReason != null) {
            item {
                EmptyLibrary(
                    reason = state.emptyReason,
                    showScan = state.showScanAction,
                    showSettings = state.showOpenSettingsAction,
                    showWebDav = state.showAddWebDavAction,
                    onScan = onRequestLocalAccess,
                    onSettings = onOpenSystemSettings,
                    onWebDav = onAddWebDav,
                )
            }
        } else {
            if (state.recentAdded.isNotEmpty()) {
                item { SectionTitle("最近添加", onClick = { onNavigate(LibraryRoutes.albums) }) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.recentAdded.take(2).forEach { track ->
                            RecentTile(track, Modifier.weight(1f))
                        }
                    }
                }
                if (state.recentAdded.size > 2) {
                    item {
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            state.recentAdded.drop(2).take(2).forEach { track ->
                                RecentTile(track, Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            if (state.recentPlayed.isNotEmpty()) {
                item { SectionTitle("最近播放") }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(state.recentPlayed, key = { it.id.value }) { RecentTile(it, Modifier.width(140.dp)) }
                    }
                }
            }
            item {
                SectionTitle("音乐来源")
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("本地音乐 · WebDAV", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = onRefresh) { Text("刷新") }
                }
            }
        }
    }
}

@Composable
fun SongsScreen(
    dependencies: LibraryFeatureDependencies,
    onBack: () -> Unit,
    onTrackMore: ((TrackSummary) -> Unit)? = null,
) {
    var sort by rememberSaveable { mutableStateOf(SongSort.TITLE) }
    val songs by remember(sort) { dependencies.libraryRepository.observeSongs(SongQuery(sort = sort)) }
        .collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        SecondaryHeader("歌曲", onBack) {
            TextButton(onClick = { sort = if (sort == SongSort.TITLE) SongSort.DATE_ADDED else SongSort.TITLE }) {
                Text(if (sort == SongSort.TITLE) "标题" else "最近添加")
            }
        }
        Text(
            text = "${songs.size} 首 · ${formatDuration(songs.mapNotNull(TrackSummary::durationMs).sum())}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        if (songs.isEmpty()) EmptyListMessage("还没有歌曲") else LazyColumn {
            items(songs, key = { it.id.value }) { track ->
                TrackRow(
                    track = track,
                    onClick = { scope.launch { dependencies.playbackGateway.dispatch(PlaybackCommand.PlayTrack(track.id)) } },
                    onMore = onTrackMore?.let { action -> { action(track) } },
                )
            }
        }
    }
}

@Composable
fun AlbumsScreen(albums: List<Album>, onBack: () -> Unit, onOpenAlbum: (Album) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        SecondaryHeader("专辑", onBack)
        if (albums.isEmpty()) EmptyListMessage("还没有专辑") else AlbumGrid(albums, onOpenAlbum)
    }
}

@Composable
fun ArtistsScreen(artists: List<Artist>, onBack: () -> Unit, onOpenArtist: (Artist) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        SecondaryHeader("歌手", onBack)
        if (artists.isEmpty()) EmptyListMessage("还没有歌手") else LazyColumn {
            items(artists, key = { it.id.value }) { artist -> ArtistRow(artist) { onOpenArtist(artist) } }
        }
    }
}

@Composable
fun FoldersScreen(
    dependencies: LibraryFeatureDependencies,
    folders: List<LibraryFolderUi>,
    selectedFolder: String?,
    folderTracks: List<TrackSummary>,
    onBack: () -> Unit,
    onOpenFolder: (String) -> Unit,
    onTrackMore: ((TrackSummary) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        SecondaryHeader(selectedFolder?.substringAfterLast('/') ?: "文件夹", onBack)
        if (selectedFolder != null) {
            Text(
                "$selectedFolder · ${folderTracks.size} 首",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            if (folderTracks.isEmpty()) EmptyListMessage("这个文件夹中没有音乐") else LazyColumn {
                items(folderTracks, key = { it.id.value }) { track ->
                    TrackRow(
                        track = track,
                        onClick = { scope.launch { dependencies.playbackGateway.dispatch(PlaybackCommand.PlayTrack(track.id)) } },
                        onMore = onTrackMore?.let { action -> { action(track) } },
                    )
                }
            }
        } else if (folders.isEmpty()) EmptyListMessage("扫描音乐后，文件夹会显示在这里") else LazyColumn {
            items(folders, key = LibraryFolderUi::path) { folder ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenFolder(folder.path) }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    Text("⌑", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(folder.path.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${folder.sourceName} · ${folder.trackCount} 首",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(Modifier.padding(start = 60.dp))
            }
        }
    }
}

@Composable
fun SearchScreen(
    dependencies: LibraryFeatureDependencies,
    onBack: () -> Unit,
    onTrackMore: ((TrackSummary) -> Unit)? = null,
    onOpenAlbum: (Album) -> Unit = {},
    onOpenArtist: (Artist) -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    val results by remember(query) { dependencies.libraryRepository.search(query.trim()) }
        .collectAsState(initial = SearchResults())
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        SecondaryHeader("搜索", onBack)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("歌曲、专辑或歌手") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        )
        if (query.isBlank()) {
            EmptyListMessage("输入关键词搜索资料库")
        } else if (results.songs.isEmpty() && results.albums.isEmpty() && results.artists.isEmpty()) {
            EmptyListMessage("没有找到相关内容")
        } else {
            LazyColumn {
                if (results.songs.isNotEmpty()) item { SearchSectionLabel("歌曲") }
                items(results.songs, key = { "song-${it.id.value}" }) { track ->
                    TrackRow(
                        track = track,
                        onClick = { scope.launch { dependencies.playbackGateway.dispatch(PlaybackCommand.PlayTrack(track.id)) } },
                        onMore = onTrackMore?.let { action -> { action(track) } },
                    )
                }
                if (results.albums.isNotEmpty()) item { SearchSectionLabel("专辑") }
                items(results.albums, key = { "album-${it.id.value}" }) { album ->
                    CompactAlbumRow(album) { onOpenAlbum(album) }
                }
                if (results.artists.isNotEmpty()) item { SearchSectionLabel("歌手") }
                items(results.artists, key = { "artist-${it.id.value}" }) { artist ->
                    ArtistRow(artist) { onOpenArtist(artist) }
                }
            }
        }
    }
}

@Composable
fun AlbumDetailScreen(
    dependencies: LibraryFeatureDependencies,
    album: Album?,
    tracks: List<TrackSummary>,
    onBack: () -> Unit,
    onTrackMore: ((TrackSummary) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        SecondaryHeader(album?.title ?: "专辑", onBack)
        if (album == null) {
            EmptyListMessage("专辑不可用")
            return@Column
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            CoverPlaceholder(Modifier.size(220.dp))
            Text(album.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
            Text("${tracks.size} 首", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (tracks.isNotEmpty()) {
                Button(onClick = { scope.launch { dependencies.playbackGateway.dispatch(PlaybackCommand.PlayTrack(tracks.first().id)) } }) {
                    Text("播放")
                }
            }
        }
        LazyColumn {
            items(tracks, key = { it.id.value }) { track ->
                TrackRow(
                    track,
                    { scope.launch { dependencies.playbackGateway.dispatch(PlaybackCommand.PlayTrack(track.id)) } },
                    onTrackMore?.let { action -> { action(track) } },
                )
            }
        }
    }
}

@Composable
fun ArtistDetailScreen(
    dependencies: LibraryFeatureDependencies,
    artist: Artist?,
    tracks: List<TrackSummary>,
    albums: List<Album>,
    onBack: () -> Unit,
    onOpenAlbum: (Album) -> Unit,
    onTrackMore: ((TrackSummary) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    if (artist == null) {
        Column(Modifier.fillMaxSize()) { SecondaryHeader("歌手", onBack); EmptyListMessage("歌手不可用") }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item { SecondaryHeader(artist.name, onBack) }
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                ArtistAvatar(artist.id, artist.name, Modifier.size(132.dp))
                Text(artist.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
            }
        }
        if (tracks.isNotEmpty()) item { SearchSectionLabel("热门歌曲") }
        items(tracks, key = { "artist-track-${it.id.value}" }) { track ->
            TrackRow(
                track,
                { scope.launch { dependencies.playbackGateway.dispatch(PlaybackCommand.PlayTrack(track.id)) } },
                onTrackMore?.let { action -> { action(track) } },
            )
        }
        if (albums.isNotEmpty()) item { SearchSectionLabel("专辑") }
        items(albums, key = { "artist-album-${it.id.value}" }) { album ->
            CompactAlbumRow(album) { onOpenAlbum(album) }
        }
    }
}

@Composable
private fun SecondaryHeader(title: String, onBack: () -> Unit, action: @Composable () -> Unit = {}) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "返回" }) { Text("‹") }
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        action()
    }
}

@Composable
private fun CategoryRow(category: LibraryCategoryUi, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(vertical = 14.dp),
    ) {
        Text(category.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        category.count?.let { Text(it.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.width(12.dp))
        Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
}

@Composable
private fun EmptyLibrary(
    reason: LibraryEmptyReason,
    showScan: Boolean,
    showSettings: Boolean,
    showWebDav: Boolean,
    onScan: () -> Unit,
    onSettings: () -> Unit,
    onWebDav: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(vertical = 44.dp),
    ) {
        CoverPlaceholder(Modifier.size(92.dp))
        Spacer(Modifier.height(18.dp))
        Text("开始建立你的资料库", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            when (reason) {
                LibraryEmptyReason.PERMISSION_REQUIRED -> "本地音频权限未开启，你仍然可以使用 WebDAV。"
                LibraryEmptyReason.LOCAL_UNAVAILABLE -> "当前设备不支持本地扫描，你仍然可以使用 WebDAV。"
                else -> "扫描设备音乐，或连接你的 WebDAV 音乐库。"
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 18.dp),
        )
        if (showScan) Button(onClick = onScan) { Text("扫描本地音乐") }
        if (showSettings) Button(onClick = onSettings) { Text("打开系统设置") }
        if (showWebDav) TextButton(onClick = onWebDav) { Text("添加 WebDAV") }
    }
}

@Composable
private fun InlineNotice(message: String, action: String, onAction: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp, end = 6.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun SectionTitle(title: String, onClick: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        onClick?.let { TextButton(onClick = it) { Text("查看全部") } }
    }
}

@Composable
private fun RecentTile(track: TrackSummary, modifier: Modifier = Modifier) {
    Column(modifier) {
        CoverPlaceholder(Modifier.fillMaxWidth().aspectRatio(1f))
        Text(track.albumTitle ?: track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp))
        Text(
            track.artistNames.joinToString().ifBlank { "未知歌手" },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun TrackRow(track: TrackSummary, onClick: () -> Unit, onMore: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { contentDescription = trackDescription(track); role = Role.Button }
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        CoverPlaceholder(Modifier.size(48.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(track.artistNames.joinToString(), track.albumTitle).filterNot { it.isNullOrBlank() }.joinToString(" · ").ifBlank { "未知歌手" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (track.downloaded) Text("↓", color = MaterialTheme.colorScheme.primary)
        onMore?.let { action ->
            TextButton(onClick = action, modifier = Modifier.semantics { contentDescription = "${track.title}的更多操作" }) {
                Text("•••")
            }
        }
    }
    HorizontalDivider(Modifier.padding(start = 80.dp))
}

@Composable
private fun AlbumGrid(albums: List<Album>, onOpenAlbum: (Album) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(148.dp),
        contentPadding = PaddingValues(20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        items(albums, key = { it.id.value }) { album ->
            Column(Modifier.clickable { onOpenAlbum(album) }) {
                CoverPlaceholder(Modifier.fillMaxWidth().aspectRatio(1f))
                Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                Text("专辑", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CompactAlbumRow(album: Album, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp)) {
        CoverPlaceholder(Modifier.size(48.dp))
        Spacer(Modifier.width(12.dp))
        Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ArtistRow(artist: Artist, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 9.dp)) {
        ArtistAvatar(artist.id, artist.name, Modifier.size(52.dp))
        Spacer(Modifier.width(14.dp))
        Text(artist.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(Modifier.padding(start = 86.dp))
}

@Composable
private fun CoverPlaceholder(modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)))
        Text("♪", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
private fun EmptyListMessage(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SearchSectionLabel(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

private fun trackDescription(track: TrackSummary): String = buildString {
    append(track.title)
    if (track.artistNames.isNotEmpty()) append(", ${track.artistNames.joinToString()}")
    if (track.downloaded) append(", 已下载")
}

private fun formatDuration(milliseconds: Long): String {
    val minutes = milliseconds / 60_000
    return when {
        minutes >= 60 -> "${minutes / 60} 小时 ${minutes % 60} 分钟"
        minutes > 0 -> "$minutes 分钟"
        else -> "时长未知"
    }
}
