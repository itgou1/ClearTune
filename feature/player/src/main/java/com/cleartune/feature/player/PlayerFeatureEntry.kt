package com.cleartune.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cleartune.core.contracts.PlaybackGateway
import com.cleartune.core.contracts.QueueRepository
import com.cleartune.core.designsystem.theme.ClearTuneDimensions
import com.cleartune.core.model.PlaybackCommand
import com.cleartune.core.model.PlaybackState
import com.cleartune.core.model.QueueCommand
import com.cleartune.core.model.QueueItemId
import com.cleartune.core.model.QueueSnapshot
import com.cleartune.core.model.RepeatMode
import com.cleartune.core.model.TrackId
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

data class PlayerTrackActionState(
    val isFavorite: Boolean = false,
    val isDownloaded: Boolean = false,
    val canFavorite: Boolean = false,
    val canDownload: Boolean = false,
)

data class PlayerFeatureDependencies(
    val playbackGateway: PlaybackGateway,
    val queueRepository: QueueRepository,
    val onQueueChanged: suspend () -> Unit = {},
    val queueTitles: Flow<Map<TrackId, String>> = flowOf(emptyMap()),
    val observeTrackActions: (TrackId) -> Flow<PlayerTrackActionState> = { flowOf(PlayerTrackActionState()) },
    val onToggleFavorite: suspend (TrackId) -> Unit = {},
    val onToggleDownload: suspend (TrackId) -> Unit = {},
    val observeLyrics: (TrackId) -> Flow<LyricsUiState> = { flowOf(LyricsUiState.Unavailable) },
    val onPlayOccurrence: suspend (QueueItemId) -> Unit = {},
)

object PlayerFeatureEntry {
    const val route = "player"

    @Composable
    fun Content(dependencies: PlayerFeatureDependencies, onNavigate: (String) -> Unit) {
        val playback by dependencies.playbackGateway.state.collectAsState()
        val queue by dependencies.queueRepository.observeQueue().collectAsState(initial = QueueSnapshot())
        val titles by dependencies.queueTitles.collectAsState(initial = emptyMap())
        val trackId = playback.currentTrack?.id
        val actions by (trackId?.let(dependencies.observeTrackActions) ?: flowOf(PlayerTrackActionState()))
            .collectAsState(initial = PlayerTrackActionState())
        val lyrics by (trackId?.let(dependencies.observeLyrics) ?: flowOf(LyricsUiState.Unavailable))
            .collectAsState(initial = LyricsUiState.Loading)
        FullPlayerScreen(
            playback = playback,
            queue = queue,
            queueTitles = titles,
            trackActions = actions,
            lyrics = lyrics,
            onPlaybackCommand = dependencies.playbackGateway::dispatch,
            onQueueCommand = { command ->
                dependencies.queueRepository.apply(command)
                dependencies.onQueueChanged()
            },
            onToggleFavorite = { trackId?.let { dependencies.onToggleFavorite(it) } },
            onToggleDownload = { trackId?.let { dependencies.onToggleDownload(it) } },
            onPlayOccurrence = dependencies.onPlayOccurrence,
            onClose = { onNavigate("back") },
        )
    }
}

@Composable
fun MiniPlayer(
    dependencies: PlayerFeatureDependencies,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playback by dependencies.playbackGateway.state.collectAsState()
    val queue by dependencies.queueRepository.observeQueue().collectAsState(initial = QueueSnapshot())
    val track = playback.currentTrack ?: return
    if (queue.items.isEmpty()) return
    val scope = rememberCoroutineScope()
    Card(
        modifier = modifier.fillMaxWidth()
            .padding(horizontal = ClearTuneDimensions.spacingSm, vertical = ClearTuneDimensions.spacingXs)
            .clickable(onClickLabel = "Open now playing") { onOpenPlayer() },
    ) {
        Column {
            Row(
                modifier = Modifier.padding(ClearTuneDimensions.spacingSm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(playback.toPlayerUiState().artwork, Modifier.size(48.dp))
                Spacer(Modifier.width(ClearTuneDimensions.spacingSm))
                Column(Modifier.weight(1f)) {
                    Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        track.artistNames.joinToString().ifBlank { "Unknown artist" },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                    )
                }
                TextButton(onClick = {
                    scope.launch {
                        dependencies.playbackGateway.dispatch(
                            if (playback.isPlaying) PlaybackCommand.Pause else PlaybackCommand.Play,
                        )
                    }
                }) { Text(if (playback.isPlaying) "Pause" else "Play") }
                TextButton(onClick = {
                    scope.launch { dependencies.playbackGateway.dispatch(PlaybackCommand.Next) }
                }) { Text("Next") }
            }
            LinearProgressIndicator(
                progress = { playback.toPlayerUiState().progress },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Playback progress" },
            )
        }
    }
}

private enum class PlayerPanel { NOW_PLAYING, LYRICS, QUEUE }

@Composable
private fun FullPlayerScreen(
    playback: PlaybackState,
    queue: QueueSnapshot,
    queueTitles: Map<TrackId, String>,
    trackActions: PlayerTrackActionState,
    lyrics: LyricsUiState,
    onPlaybackCommand: suspend (PlaybackCommand) -> Unit,
    onQueueCommand: suspend (QueueCommand) -> Unit,
    onToggleFavorite: suspend () -> Unit,
    onToggleDownload: suspend () -> Unit,
    onPlayOccurrence: suspend (QueueItemId) -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var panel by remember { mutableStateOf(PlayerPanel.NOW_PLAYING) }
    val ui = playback.toPlayerUiState()
    Column(
        modifier = Modifier.fillMaxSize().padding(ClearTuneDimensions.spacingMd),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("Close") }
            Spacer(Modifier.weight(1f))
            Text("Now playing", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(64.dp))
        }
        when (panel) {
            PlayerPanel.QUEUE -> QueueContent(
                queue, queueTitles, onQueueCommand, onPlayOccurrence, Modifier.weight(1f),
            )
            PlayerPanel.LYRICS -> LyricsContent(lyrics, Modifier.weight(1f))
            PlayerPanel.NOW_PLAYING -> {
                Spacer(Modifier.weight(1f))
                Artwork(ui.artwork, Modifier.fillMaxWidth().height(280.dp))
                Spacer(Modifier.height(ClearTuneDimensions.spacingLg))
                Text(playback.currentTrack?.title ?: "Nothing playing", style = MaterialTheme.typography.headlineSmall)
                Text(
                    playback.currentTrack?.artistNames?.joinToString().orEmpty().ifBlank { "Choose a track" },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = ui.progress,
                    onValueChange = { progress -> playback.durationMs?.let { duration ->
                        scope.launch { onPlaybackCommand(PlaybackCommand.SeekTo((duration * progress).toLong())) }
                    } },
                    modifier = Modifier.semantics { contentDescription = "Playback progress" },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(ui.positionLabel, style = MaterialTheme.typography.labelSmall)
                    Text(ui.durationLabel, style = MaterialTheme.typography.labelSmall)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { scope.launch { onPlaybackCommand(PlaybackCommand.Previous) } }) { Text("Previous") }
                    Button(onClick = { scope.launch {
                        onPlaybackCommand(if (playback.isPlaying) PlaybackCommand.Pause else PlaybackCommand.Play)
                    } }) { Text(if (playback.isPlaying) "Pause" else "Play") }
                    TextButton(onClick = { scope.launch { onPlaybackCommand(PlaybackCommand.Next) } }) { Text("Next") }
                }
                ui.error?.let { error ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(ClearTuneDimensions.spacingSm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(error.message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { scope.launch { onPlaybackCommand(PlaybackCommand.Play) } }) {
                                Text("Retry")
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(
                        enabled = trackActions.canFavorite,
                        onClick = { scope.launch { onToggleFavorite() } },
                    ) { Text(if (trackActions.isFavorite) "Unfavorite" else "Favorite") }
                    TextButton(
                        enabled = trackActions.canDownload,
                        onClick = { scope.launch { onToggleDownload() } },
                    ) { Text(if (trackActions.isDownloaded) "Remove download" else "Download") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { scope.launch {
                        onPlaybackCommand(PlaybackCommand.SetShuffle(!playback.shuffleEnabled))
                    } }) { Text(if (playback.shuffleEnabled) "Shuffle on" else "Shuffle off") }
                    TextButton(onClick = {
                        val next = when (playback.repeatMode) {
                            RepeatMode.OFF -> RepeatMode.ALL
                            RepeatMode.ALL -> RepeatMode.ONE
                            RepeatMode.ONE -> RepeatMode.OFF
                        }
                        scope.launch { onPlaybackCommand(PlaybackCommand.SetRepeat(next)) }
                    }) { Text("Repeat ${playback.repeatMode.name.lowercase()}") }
                }
                Spacer(Modifier.weight(1f))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = { panel = PlayerPanel.NOW_PLAYING }) { Text("Player") }
            TextButton(onClick = { panel = PlayerPanel.LYRICS }) { Text("Lyrics") }
            TextButton(onClick = { panel = PlayerPanel.QUEUE }) { Text("Queue ${queue.items.size}") }
        }
    }
}

@Composable
private fun Artwork(artwork: ArtworkPresentation, modifier: Modifier = Modifier) {
    var failed by remember(artwork) { mutableStateOf(false) }
    Box(
        modifier = modifier.clip(RoundedCornerShape(ClearTuneDimensions.artworkCorner))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics { contentDescription = "Album artwork" },
        contentAlignment = Alignment.Center,
    ) {
        if (artwork is ArtworkPresentation.Remote && !failed) {
            AsyncImage(
                model = artwork.reference,
                contentDescription = "Album artwork",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onError = { failed = true },
            )
        } else {
            Text(
                if (artwork is ArtworkPresentation.Fallback) artwork.monogram else "♪",
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun LyricsContent(lyrics: LyricsUiState, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        when (lyrics) {
            LyricsUiState.Loading -> Text("Loading lyrics")
            LyricsUiState.Unavailable -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Lyrics unavailable", style = MaterialTheme.typography.headlineSmall)
                Text("Playback continues", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is LyricsUiState.Available -> LazyColumn(Modifier.fillMaxSize()) {
                items(lyrics.lines) { line -> Text(line, Modifier.padding(vertical = ClearTuneDimensions.spacingXs)) }
            }
        }
    }
}

@Composable
private fun QueueContent(
    queue: QueueSnapshot,
    titles: Map<TrackId, String>,
    onCommand: suspend (QueueCommand) -> Unit,
    onPlayOccurrence: suspend (QueueItemId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }
    if (queue.items.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) { Text("Playback queue is empty") }
        return
    }
    val rows = queue.toQueueRows(titles)
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { confirmClear = true }) { Text("Clear queue") }
        }
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(queue.items, key = { _, item -> item.id.value }) { index, item ->
                val row = rows[index]
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(onClickLabel = row.playActionLabel) { scope.launch { onPlayOccurrence(item.id) } }
                        .semantics {
                            customActions = listOf(
                                CustomAccessibilityAction(row.moveUpActionLabel) {
                                    if (index > 0) scope.launch { onCommand(QueueCommand.Move(item.id, index - 1)) }
                                    index > 0
                                },
                                CustomAccessibilityAction(row.moveDownActionLabel) {
                                    if (index < queue.items.lastIndex) scope.launch {
                                        onCommand(QueueCommand.Move(item.id, index + 1))
                                    }
                                    index < queue.items.lastIndex
                                },
                                CustomAccessibilityAction(row.removeActionLabel) {
                                    scope.launch { onCommand(QueueCommand.Remove(item.id)) }
                                    true
                                },
                            )
                        }
                        .padding(vertical = ClearTuneDimensions.spacingXs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (row.isCurrent) "Playing" else "${index + 1}", Modifier.width(72.dp))
                    Text(row.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(enabled = index > 0, onClick = {
                        scope.launch { onCommand(QueueCommand.Move(item.id, index - 1)) }
                    }) { Text("Up") }
                    TextButton(enabled = index < queue.items.lastIndex, onClick = {
                        scope.launch { onCommand(QueueCommand.Move(item.id, index + 1)) }
                    }) { Text("Down") }
                    TextButton(onClick = { scope.launch { onCommand(QueueCommand.Remove(item.id)) } }) {
                        Text("Remove")
                    }
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear playback queue?") },
            text = { Text("This removes every queued occurrence.") },
            confirmButton = { TextButton(onClick = {
                scope.launch { onCommand(QueueCommand.Replace(emptyList())) }
                confirmClear = false
            }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}
