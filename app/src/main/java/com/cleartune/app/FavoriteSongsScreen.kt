package com.cleartune.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleartune.app.library.*
import com.cleartune.app.share.ShareKind
import com.cleartune.app.share.ShareTarget
import com.cleartune.core.model.Playlist
import com.cleartune.core.model.Song

@Composable
internal fun FavoriteSongsScreen(
    songs: List<Song>,
    viewModel: MusicViewModel,
    onBack: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
    allSongs: List<Song> = songs,
    playlists: List<Playlist> = emptyList(),
    onDownload: (List<Song>) -> Unit = {},
    onPlayNext: (Song) -> Unit = {},
    onShare: (ShareTarget) -> Unit = {},
) {
    val title = stringResource(R.string.favorites)
    val sortFlow = remember(viewModel) { viewModel.favoritesSort() }
    val savedSort by key(viewModel) { sortFlow.collectAsStateWithLifecycle(initialValue = null) }
    var pendingSort by remember(viewModel) { mutableStateOf<PlaylistSort?>(null) }
    LaunchedEffect(savedSort) { if (pendingSort == savedSort) pendingSort = null }
    val sort = pendingSort ?: savedSort ?: PlaylistSort()
    val sorted = remember(songs, sort) { sortedFavoriteEntries(songs, sort) }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var confirmRemoval by remember { mutableStateOf(false) }
    val selected = selection.intersect(songs.map { it.id }.toSet())
    BackHandler(selecting) { selecting = false; selection = emptySet() }
    DetailScaffold(
        title = if (selecting) stringResource(R.string.selected_songs_count, selected.size) else title,
        onBack = { if (selecting) { selecting = false; selection = emptySet() } else onBack() },
        actions = {
            if (!selecting) IconButton(enabled = songs.isNotEmpty(), onClick = {
                onShare(ShareTarget(ShareKind.PLAYLIST, "favorites", title, "${songs.size} 首歌曲",
                    songs.firstOrNull()?.coverArtId, songs.size, sorted.map { it.id }))
            }) { Icon(Icons.Rounded.Share, "分享我喜欢的音乐") }
        },
        bottomBar = {
            if (selecting) PlaylistSelectionBar(
                allSelected = selected.size == songs.size && songs.isNotEmpty(),
                removeEnabled = selected.isNotEmpty(),
                onSelectAll = { selection = if (selected.size == songs.size) emptySet() else songs.map { it.id }.toSet() },
                onRemove = { confirmRemoval = true }, removeLabel = "取消喜欢",
            )
        },
    ) {
        item {
            DetailHeader(title, stringResource(R.string.song_count, songs.size), songs.firstOrNull()?.coverArtId,
                viewModel, previewSize = 192)
            if (!selecting) PlaylistDetailActions(
                hasSongs = songs.isNotEmpty() && savedSort != null,
                onPlay = { onPlay(sorted, 0) }, onDownload = { onDownload(sorted) },
                sort = sort, onSortChange = { pendingSort = it; viewModel.setFavoritesSort(it) },
                favorites = true, onAdd = { viewModel.resetFavoriteSongAddState(); showAdd = true },
                moreExpanded = more, onMoreExpandedChange = { more = it },
                onManage = { more = false; selecting = true; selection = emptySet() },
            )
        }
        if (songs.isEmpty()) item { Text(stringResource(R.string.no_favorite_songs), Modifier.padding(20.dp)) }
        itemsIndexed(if (savedSort == null) emptyList() else sorted, key = { _, song -> song.id }) { index, song ->
            MusicSongRow(song = song, viewModel = viewModel,
                selected = if (selecting) song.id in selected else null,
                onClick = {
                    if (selecting) selection = if (song.id in selected) selection - song.id else selection + song.id
                    else onPlay(sorted, index)
                },
                onLongClick = if (selecting) null else ({ selecting = true; selection = setOf(song.id) }),
                actions = {
                    SongActionsMenu(song = song, playlists = playlists, viewModel = viewModel,
                        onToggleLike = { viewModel.setSongFavorite(song, false) },
                        onAddToPlaylist = { viewModel.addPlaylistSong(it, song.id) },
                        onPlayNext = { onPlayNext(song) }, onDownload = { onDownload(listOf(song)) },
                        onShare = { onShare(song.shareTarget()) })
                })
        }
    }
    if (showAdd) PlaylistSongPicker(Playlist("favorites", title, songs.size), allSongs, songs, viewModel,
        onDismiss = { showAdd = false; viewModel.resetFavoriteSongAddState() }, favorites = true)
    if (confirmRemoval) AlertDialog(onDismissRequest = { confirmRemoval = false },
        title = { Text("取消喜欢这 ${selected.size} 首歌曲？") },
        text = { Text("歌曲会从「我喜欢的音乐」移除，爱心状态同步取消。") },
        confirmButton = { TextButton(onClick = {
            viewModel.removeFavoriteSongs(songs.filter { it.id in selected })
            confirmRemoval = false; selecting = false; selection = emptySet()
        }) { Text("取消喜欢") } },
        dismissButton = { TextButton(onClick = { confirmRemoval = false }) { Text("保留") } })
}
