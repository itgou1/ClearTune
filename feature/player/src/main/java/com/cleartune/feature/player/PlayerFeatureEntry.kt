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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cleartune.core.contracts.PlaybackGateway
import com.cleartune.core.contracts.QueueRepository
import com.cleartune.core.designsystem.theme.ClearTuneDimensions
import com.cleartune.core.model.PlaybackCommand
import com.cleartune.core.model.PlaybackState
import com.cleartune.core.model.QueueCommand
import com.cleartune.core.model.QueueSnapshot
import com.cleartune.core.model.RepeatMode
import kotlinx.coroutines.launch

data class PlayerFeatureDependencies(
    val playbackGateway: PlaybackGateway,
    val queueRepository: QueueRepository,
    val onQueueChanged: suspend () -> Unit = {},
)

object PlayerFeatureEntry {
    const val route = "player"

    @Composable
    fun Content(
        dependencies: PlayerFeatureDependencies,
        onNavigate: (String) -> Unit,
    ) {
        val playback by dependencies.playbackGateway.state.collectAsState()
        val queue by dependencies.queueRepository.observeQueue().collectAsState(initial = QueueSnapshot())
        FullPlayerScreen(
            playback = playback,
            queue = queue,
            onPlaybackCommand = { command -> dependencies.playbackGateway.dispatch(command) },
            onQueueCommand = { command ->
                dependencies.queueRepository.apply(command)
                dependencies.onQueueChanged()
            },
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
    if (queue.items.isEmpty()) return
    val track = playback.currentTrack ?: return
    val scope = rememberCoroutineScope()
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ClearTuneDimensions.spacingSm, vertical = ClearTuneDimensions.spacingXs)
            .clickable(onClickLabel = "打开正在播放") { onOpenPlayer() },
    ) {
        Row(
            modifier = Modifier.padding(ClearTuneDimensions.spacingSm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtworkPlaceholder(Modifier.size(48.dp))
            Spacer(Modifier.width(ClearTuneDimensions.spacingSm))
            Column(Modifier.weight(1f)) {
                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    track.artistNames.joinToString().ifBlank { "未知艺术家" },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
            TextButton(
                onClick = {
                    scope.launch {
                        dependencies.playbackGateway.dispatch(
                            if (playback.isPlaying) PlaybackCommand.Pause else PlaybackCommand.Play,
                        )
                    }
                },
                modifier = Modifier.height(ClearTuneDimensions.minimumTouchTarget),
            ) { Text(if (playback.isPlaying) "暂停" else "播放") }
            TextButton(
                onClick = { scope.launch { dependencies.playbackGateway.dispatch(PlaybackCommand.Next) } },
                modifier = Modifier.height(ClearTuneDimensions.minimumTouchTarget),
            ) { Text("下一首") }
        }
    }
}

private enum class PlayerPanel { NOW_PLAYING, LYRICS, QUEUE }

@Composable
private fun FullPlayerScreen(
    playback: PlaybackState,
    queue: QueueSnapshot,
    onPlaybackCommand: suspend (PlaybackCommand) -> Unit,
    onQueueCommand: suspend (QueueCommand) -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var panel by remember { mutableStateOf(PlayerPanel.NOW_PLAYING) }
    val ui = playback.toPlayerUiState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(ClearTuneDimensions.spacingMd),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose, modifier = Modifier.height(ClearTuneDimensions.minimumTouchTarget)) {
                Text("关闭")
            }
            Spacer(Modifier.weight(1f))
            Text("正在播放", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(64.dp))
        }
        when (panel) {
            PlayerPanel.QUEUE -> QueueContent(queue, onQueueCommand, Modifier.weight(1f))
            PlayerPanel.LYRICS -> EmptyLyricsContent()
            PlayerPanel.NOW_PLAYING -> {
                Spacer(Modifier.weight(1f))
                ArtworkPlaceholder(Modifier.fillMaxWidth().height(280.dp))
                Spacer(Modifier.height(ClearTuneDimensions.spacingLg))
                Text(playback.currentTrack?.title ?: "尚未播放", style = MaterialTheme.typography.headlineSmall)
                Text(
                    playback.currentTrack?.artistNames?.joinToString().orEmpty().ifBlank { "从曲库选择一首音乐" },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = ui.progress,
                    onValueChange = { progress ->
                        playback.durationMs?.let { duration ->
                            scope.launch { onPlaybackCommand(PlaybackCommand.SeekTo((duration * progress).toLong())) }
                        }
                    },
                    modifier = Modifier.semantics { contentDescription = "播放进度" },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(ui.positionLabel, style = MaterialTheme.typography.labelSmall)
                    Text(ui.durationLabel, style = MaterialTheme.typography.labelSmall)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { scope.launch { onPlaybackCommand(PlaybackCommand.Previous) } }) { Text("上一首") }
                    Button(onClick = {
                        scope.launch {
                            onPlaybackCommand(if (playback.isPlaying) PlaybackCommand.Pause else PlaybackCommand.Play)
                        }
                    }) { Text(if (playback.isPlaying) "暂停" else "播放") }
                    TextButton(onClick = { scope.launch { onPlaybackCommand(PlaybackCommand.Next) } }) { Text("下一首") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = {
                        scope.launch { onPlaybackCommand(PlaybackCommand.SetShuffle(!playback.shuffleEnabled)) }
                    }) { Text(if (playback.shuffleEnabled) "随机：开" else "随机：关") }
                    TextButton(onClick = {
                        val next = when (playback.repeatMode) {
                            RepeatMode.OFF -> RepeatMode.ALL
                            RepeatMode.ALL -> RepeatMode.ONE
                            RepeatMode.ONE -> RepeatMode.OFF
                        }
                        scope.launch { onPlaybackCommand(PlaybackCommand.SetRepeat(next)) }
                    }) { Text("循环：${playback.repeatMode.name}") }
                }
                Spacer(Modifier.weight(1f))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = { panel = PlayerPanel.NOW_PLAYING }) { Text("播放") }
            TextButton(onClick = { panel = PlayerPanel.LYRICS }) { Text("歌词") }
            TextButton(onClick = { panel = PlayerPanel.QUEUE }) { Text("队列 ${queue.items.size}") }
        }
    }
}

@Composable
private fun ArtworkPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(ClearTuneDimensions.artworkCorner))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text("♪", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun EmptyLyricsContent() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("暂无歌词", style = MaterialTheme.typography.headlineSmall)
            Text("播放不受影响", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun QueueContent(
    queue: QueueSnapshot,
    onCommand: suspend (QueueCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    if (queue.items.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) { Text("播放队列为空") }
        return
    }
    LazyColumn(modifier.fillMaxWidth()) {
        itemsIndexed(queue.items, key = { _, item -> item.id.value }) { index, item ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = ClearTuneDimensions.spacingXs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (index == queue.currentIndex) "正在播放" else "${index + 1}", Modifier.width(72.dp))
                Text(item.trackId.value, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(
                    enabled = index > 0,
                    onClick = { scope.launch { onCommand(QueueCommand.Move(item.id, index - 1)) } },
                ) { Text("上移") }
                TextButton(
                    enabled = index < queue.items.lastIndex,
                    onClick = { scope.launch { onCommand(QueueCommand.Move(item.id, index + 1)) } },
                ) { Text("下移") }
                TextButton(onClick = { scope.launch { onCommand(QueueCommand.Remove(item.id)) } }) { Text("移除") }
            }
        }
    }
}
