package com.cleartune.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import com.cleartune.app.library.MusicViewModel
import com.cleartune.app.library.LyricsUiState
import com.cleartune.app.player.PlayerViewModel
import com.cleartune.core.model.PlaybackMode
import com.cleartune.core.player.PlaybackStatus
import com.cleartune.core.player.PlayerUiState

@Composable
internal fun MiniPlayer(
    state: PlayerUiState,
    musicViewModel: MusicViewModel,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onQueue: () -> Unit,
) {
    val song = state.currentSong ?: return
    Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
        val durationMs = state.durationMs.takeIf { it > 0 } ?: song.durationSeconds * 1_000
        val progress = if (durationMs > 0) {
            state.positionMs.toFloat() / durationMs
        } else {
            0f
        }
        Column(modifier = Modifier.background(clearTuneGradient())) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpen)
                    .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverArt(song.coverArtId, song.title, musicViewModel, Modifier.size(48.dp))
                Spacer(Modifier.size(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    Text(
                        song.artistName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ClearTuneTonalIconButton(onClick = onToggle) {
                    Icon(
                        if (state.status == PlaybackStatus.PLAYING) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(
                            if (state.status == PlaybackStatus.PLAYING) R.string.pause_action else R.string.play_action,
                        ),
                    )
                }
                ClearTuneTonalIconButton(onClick = onQueue) {
                    Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = stringResource(R.string.play_queue))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NowPlayingScreen(
    state: PlayerUiState,
    playerViewModel: PlayerViewModel,
    musicViewModel: MusicViewModel,
    lyricsState: LyricsUiState,
    isFavorite: Boolean,
    onBack: () -> Unit,
    onQueue: () -> Unit,
    onEqualizer: () -> Unit,
    onFavorite: (com.cleartune.core.model.Song, Boolean) -> Unit,
    onDownload: (com.cleartune.core.model.Song) -> Unit,
) {
    val song = state.currentSong
    var showLyrics by remember(song?.id) { mutableStateOf(false) }
    var showDetails by remember(song?.id) { mutableStateOf(false) }
    Scaffold(
        topBar = {
            ClearTuneTopAppBar(title = stringResource(R.string.now_playing), onBack = onBack)
        },
    ) { padding ->
        if (song == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { Text(stringResource(R.string.empty_queue)) }
            return@Scaffold
        }
        var seekPosition by remember(song.id, state.positionMs) {
            mutableFloatStateOf(state.positionMs.toFloat())
        }
        val durationMs = state.durationMs.takeIf { it > 0 } ?: song.durationSeconds * 1_000
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(clearTuneGradient())
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(292.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { showLyrics = !showLyrics },
            ) {
                if (showLyrics) {
                    LyricsArtwork(
                        lyricsState = lyricsState,
                        positionMs = state.positionMs,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    CoverArt(song.coverArtId, song.title, musicViewModel, Modifier.fillMaxSize())
                }
            }
            Spacer(Modifier.height(20.dp))
            Box(modifier = Modifier.fillMaxWidth()) {
                Text(
                    song.title,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 52.dp),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(
                    onClick = { onFavorite(song, !isFavorite) },
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) {
                    Icon(
                        if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = stringResource(if (isFavorite) R.string.unlike_song else R.string.like_song),
                        tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(song.artistName, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                listOfNotNull(song.suffix?.uppercase(), song.bitRate?.let { "$it kbps" }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Slider(
                value = seekPosition.coerceIn(0f, durationMs.coerceAtLeast(1).toFloat()),
                onValueChange = { seekPosition = it },
                onValueChangeFinished = { playerViewModel.seekTo(seekPosition.toLong()) },
                valueRange = 0f..durationMs.coerceAtLeast(1).toFloat(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(seekPosition.toLong()))
                Text(formatTime(durationMs))
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ClearTuneTonalIconButton(onClick = playerViewModel::previous) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = stringResource(R.string.previous_song))
                }
                FilledIconButton(
                    onClick = playerViewModel::togglePlayPause,
                    modifier = Modifier.size(72.dp),
                ) {
                    Icon(
                        if (state.status == PlaybackStatus.PLAYING) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(
                            if (state.status == PlaybackStatus.PLAYING) R.string.pause_action else R.string.play_action,
                        ),
                        modifier = Modifier.size(38.dp),
                    )
                }
                ClearTuneTonalIconButton(onClick = playerViewModel::next) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = stringResource(R.string.next_song))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ClearTuneTonalIconButton(onClick = playerViewModel::cycleMode) {
                    Icon(state.mode.icon(), contentDescription = state.mode.label())
                }
                ClearTuneTonalIconButton(onClick = { onDownload(song) }) {
                    Icon(Icons.Rounded.Download, contentDescription = stringResource(R.string.download_current_song))
                }
                ClearTuneTonalIconButton(onClick = onEqualizer) {
                    Icon(Icons.Rounded.GraphicEq, contentDescription = stringResource(R.string.equalizer))
                }
                ClearTuneTonalIconButton(onClick = onQueue) {
                    Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = stringResource(R.string.play_queue))
                }
                ClearTuneTonalIconButton(onClick = { showDetails = true }) {
                    Icon(Icons.Rounded.Info, contentDescription = stringResource(R.string.song_details))
                }
            }
        }
    }
    if (showDetails && song != null) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text(stringResource(R.string.song_details)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(song.title, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.song_detail_artist, song.artistName))
                    Text(stringResource(R.string.song_detail_album, song.albumName))
                    Text(stringResource(R.string.song_detail_duration, formatTime(song.durationSeconds * 1_000)))
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
                TextButton(onClick = { showDetails = false }) {
                    Text(stringResource(R.string.close_action))
                }
            },
        )
    }
}

@Composable
private fun LyricsArtwork(
    lyricsState: LyricsUiState,
    positionMs: Long,
    modifier: Modifier = Modifier,
) {
    val lines = lyricsState.lyrics?.lines.orEmpty()
    val activeIndex = lines.indexOfLast { line ->
        line.startMs?.let { it <= positionMs } == true
    }
    val listState = rememberLazyListState()
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) {
            listState.animateScrollToItem(activeIndex, scrollOffset = -120)
        }
    }
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        when {
            lyricsState.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            lines.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(lyricsState.message ?: stringResource(R.string.no_lyrics))
            }
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                itemsIndexed(lines) { index, line ->
                    Text(
                        text = line.text,
                        style = if (index == activeIndex) {
                            MaterialTheme.typography.titleLarge
                        } else {
                            MaterialTheme.typography.bodyLarge
                        },
                        color = if (index == activeIndex) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LyricsScreen(
    state: LyricsUiState,
    playerState: PlayerUiState,
    onSeek: (Long) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            ClearTuneTopAppBar(title = stringResource(R.string.lyrics), onBack = onBack)
        },
    ) { padding ->
        when {
            state.loading -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { Text(stringResource(R.string.loading_lyrics)) }
            state.lyrics?.lines.isNullOrEmpty() -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { Text(state.message ?: stringResource(R.string.no_lyrics)) }
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val lines = state.lyrics.lines
                itemsIndexed(lines) { _, line ->
                    val active = line.startMs?.let { start ->
                        val next = lines.firstOrNull { (it.startMs ?: Long.MAX_VALUE) > start }?.startMs
                        playerState.positionMs >= start && (next == null || playerState.positionMs < next)
                    } == true
                    Text(
                        text = line.text,
                        style = if (active) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = line.startMs != null) { line.startMs?.let(onSeek) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QueueScreen(
    state: PlayerUiState,
    playerViewModel: PlayerViewModel,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            ClearTuneTopAppBar(
                title = stringResource(R.string.play_queue),
                onBack = onBack,
                actions = {
                    IconButton(onClick = playerViewModel::clearQueue) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = stringResource(R.string.clear_queue))
                    }
                },
            )
        },
    ) { padding ->
        if (state.queue.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { Text(stringResource(R.string.empty_queue)) }
        } else {
            LazyColumn(modifier = Modifier.padding(padding)) {
                itemsIndexed(state.queue, key = { _, song -> song.id }) { index, song ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { playerViewModel.play(state.queue, index) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (index == state.currentIndex) {
                            Icon(
                                Icons.Rounded.Equalizer,
                                contentDescription = stringResource(R.string.now_playing),
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Text("${index + 1}", modifier = Modifier.size(32.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(song.artistName, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(
                            onClick = { playerViewModel.move(index, index - 1) },
                            enabled = index > 0,
                        ) {
                            Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = stringResource(R.string.move_up))
                        }
                        IconButton(
                            onClick = { playerViewModel.move(index, index + 1) },
                            enabled = index < state.queue.lastIndex,
                        ) {
                            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.move_down))
                        }
                        IconButton(onClick = { playerViewModel.remove(index) }) {
                            Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.remove_action))
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ClearTuneTonalIconButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        ),
        content = content,
    )
}

@Composable
private fun PlaybackMode.label(): String = stringResource(when (this) {
    PlaybackMode.SEQUENTIAL -> R.string.mode_sequential
    PlaybackMode.REPEAT_ALL -> R.string.mode_repeat_all
    PlaybackMode.REPEAT_ONE -> R.string.mode_repeat_one
    PlaybackMode.SHUFFLE -> R.string.mode_shuffle
})

private fun PlaybackMode.icon(): ImageVector = when (this) {
    PlaybackMode.SEQUENTIAL -> Icons.AutoMirrored.Rounded.PlaylistPlay
    PlaybackMode.REPEAT_ALL -> Icons.Rounded.Repeat
    PlaybackMode.REPEAT_ONE -> Icons.Rounded.RepeatOne
    PlaybackMode.SHUFFLE -> Icons.Rounded.Shuffle
}

private fun formatTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1_000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
