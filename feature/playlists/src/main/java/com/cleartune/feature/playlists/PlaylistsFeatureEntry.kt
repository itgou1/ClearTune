package com.cleartune.feature.playlists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.cleartune.core.contracts.PlaybackGateway
import com.cleartune.core.contracts.PlaylistRepository
import com.cleartune.core.contracts.QueueRepository
import com.cleartune.core.designsystem.theme.ClearTuneDimensions
import com.cleartune.core.model.PlaylistCommand
import com.cleartune.core.model.PlaybackCommand
import com.cleartune.core.model.PlaylistSummary
import com.cleartune.core.model.QueueCommand
import com.cleartune.core.model.PlaylistId
import kotlinx.coroutines.launch

data class PlaylistsFeatureDependencies(
    val playlistRepository: PlaylistRepository,
    val playbackGateway: PlaybackGateway,
    val queueRepository: QueueRepository? = null,
)

object PlaylistsFeatureEntry {
    const val route = "playlists"

    @Composable
    fun Content(
        dependencies: PlaylistsFeatureDependencies,
        onNavigate: (String) -> Unit,
    ) {
        val playlists by dependencies.playlistRepository.observePlaylists().collectAsState(initial = emptyList())
        val scope = rememberCoroutineScope()
        var editor by remember { mutableStateOf<PlaylistSummary?>(null) }
        var creating by remember { mutableStateOf(false) }
        var deleting by remember { mutableStateOf<PlaylistSummary?>(null) }
        var selectedPlaylistId by remember { mutableStateOf<PlaylistId?>(null) }
        val detailsProvider = dependencies.playlistRepository as? PlaylistDetailsProvider
        selectedPlaylistId?.let { playlistId ->
            if (detailsProvider != null) {
                val details by detailsProvider.observePlaylist(playlistId).collectAsState(initial = null)
                PlaylistDetailsScreen(
                    details = details,
                    dependencies = dependencies,
                    onBack = { selectedPlaylistId = null },
                )
                return
            }
        }
        Column(Modifier.fillMaxSize().padding(ClearTuneDimensions.spacingMd)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onNavigate("back") }) { Text("返回") }
                Text("歌单", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                Button(onClick = { creating = true }) { Text("新建") }
            }
            if (playlists.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("还没有歌单", style = MaterialTheme.typography.titleLarge)
                        Text("创建歌单，按自己的方式整理音乐")
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(ClearTuneDimensions.spacingSm)) {
                    items(playlists, key = { it.id.value }) { playlist ->
                        Card(Modifier.fillMaxWidth().clickable { selectedPlaylistId = playlist.id }) {
                            Row(
                                Modifier.fillMaxWidth().padding(ClearTuneDimensions.spacingMd),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(playlist.name, style = MaterialTheme.typography.titleMedium)
                                    Text("${playlist.trackCount} 首歌曲", style = MaterialTheme.typography.bodySmall)
                                }
                                TextButton(onClick = { editor = playlist }) { Text("重命名") }
                                TextButton(onClick = { deleting = playlist }) { Text("删除") }
                            }
                        }
                    }
                }
            }
        }
        if (creating || editor != null) {
            PlaylistNameDialog(
                title = if (creating) "新建歌单" else "重命名歌单",
                initialName = editor?.name.orEmpty(),
                reservedNames = playlists.filterNot { it.id == editor?.id }.map { it.name },
                onDismiss = { creating = false; editor = null },
                onConfirm = { name ->
                    scope.launch {
                        if (creating) dependencies.playlistRepository.apply(PlaylistCommand.Create(name))
                        else editor?.let { dependencies.playlistRepository.apply(PlaylistCommand.Rename(it.id, name)) }
                    }
                    creating = false
                    editor = null
                },
            )
        }
        deleting?.let { playlist ->
            AlertDialog(
                onDismissRequest = { deleting = null },
                title = { Text("删除“${playlist.name}”？") },
                text = { Text("只删除歌单，不会删除音乐文件。") },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch { dependencies.playlistRepository.apply(PlaylistCommand.Delete(playlist.id)) }
                        deleting = null
                    }) { Text("删除") }
                },
                dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
            )
        }
    }
}

@Composable
private fun PlaylistDetailsScreen(
    details: PlaylistDetails?,
    dependencies: PlaylistsFeatureDependencies,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val trackIds = details?.items?.map { it.trackId }.orEmpty()
    Column(Modifier.fillMaxSize().padding(ClearTuneDimensions.spacingMd)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("返回") }
            Text(details?.name ?: "歌单", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ClearTuneDimensions.spacingSm)) {
            Button(
                enabled = trackIds.isNotEmpty(),
                onClick = {
                    scope.launch {
                        dependencies.queueRepository?.apply(QueueCommand.Replace(trackIds))
                        trackIds.firstOrNull()?.let { dependencies.playbackGateway.dispatch(PlaybackCommand.PlayTrack(it)) }
                    }
                },
            ) { Text("播放") }
            TextButton(
                enabled = trackIds.size > 1,
                onClick = {
                    scope.launch {
                        val shuffled = trackIds.shuffled()
                        dependencies.queueRepository?.apply(QueueCommand.Replace(shuffled))
                        shuffled.firstOrNull()?.let { dependencies.playbackGateway.dispatch(PlaybackCommand.PlayTrack(it)) }
                    }
                },
            ) { Text("随机播放") }
        }
        if (details == null || details.items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("歌单中还没有歌曲") }
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                itemsIndexed(details.items, key = { _, item -> item.id.value }) { index, item ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = ClearTuneDimensions.spacingXs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${index + 1}", modifier = Modifier.padding(end = ClearTuneDimensions.spacingSm))
                        Text(item.trackId.value, modifier = Modifier.weight(1f))
                        TextButton(
                            enabled = index > 0,
                            onClick = {
                                scope.launch {
                                    dependencies.playlistRepository.apply(
                                        PlaylistCommand.MoveTrack(details.id, item.id, index - 1),
                                    )
                                }
                            },
                        ) { Text("上移") }
                        TextButton(
                            enabled = index < details.items.lastIndex,
                            onClick = {
                                scope.launch {
                                    dependencies.playlistRepository.apply(
                                        PlaylistCommand.MoveTrack(details.id, item.id, index + 1),
                                    )
                                }
                            },
                        ) { Text("下移") }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    dependencies.playlistRepository.apply(PlaylistCommand.RemoveTrack(details.id, item.id))
                                }
                            },
                        ) { Text("移除") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistNameDialog(
    title: String,
    initialName: String,
    reservedNames: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    val duplicate = reservedNames.any { it.equals(name.trim(), ignoreCase = true) }
    val valid = name.trim().length in 1..100 && !duplicate
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(100) },
                label = { Text("名称") },
                singleLine = true,
                supportingText = { Text(if (duplicate) "已有同名歌单" else "${name.length}/100") },
            )
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onConfirm(name) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
