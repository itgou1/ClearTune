package com.cleartune.feature.playlists

import android.net.Uri
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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import com.cleartune.core.contracts.PlaybackGateway
import com.cleartune.core.contracts.PlaylistRepository
import com.cleartune.core.contracts.QueueRepository
import com.cleartune.core.designsystem.theme.ClearTuneDimensions
import com.cleartune.core.model.PlaybackCommand
import com.cleartune.core.model.PlaylistCommand
import com.cleartune.core.model.PlaylistId
import com.cleartune.core.model.PlaylistSummary
import com.cleartune.core.model.QueueCommand
import com.cleartune.core.model.TrackId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

data class PlaylistsFeatureDependencies(
    val playlistRepository: PlaylistRepository,
    val playbackGateway: PlaybackGateway,
    val queueRepository: QueueRepository? = null,
    val detailsProvider: PlaylistDetailsProvider? = null,
    val trackTitles: Flow<Map<TrackId, String>> = flowOf(emptyMap()),
)

object PlaylistsFeatureEntry {
    const val route = "playlists"
    const val playlistIdArgument = "playlistId"
    const val detailRoutePattern = "$route/{$playlistIdArgument}"

    fun detailRoute(playlistId: PlaylistId): String = "$route/${Uri.encode(playlistId.value)}"

    @Composable
    fun Content(
        dependencies: PlaylistsFeatureDependencies,
        onNavigate: (String) -> Unit,
        playlistId: PlaylistId? = null,
    ) {
        if (playlistId != null) {
            val details by (dependencies.detailsProvider?.observePlaylist(playlistId) ?: flowOf(null))
                .collectAsState(initial = null)
            val titles by dependencies.trackTitles.collectAsState(initial = emptyMap())
            PlaylistDetailsScreen(details, titles, dependencies, onBack = { onNavigate("back") })
            return
        }
        val playlists by dependencies.playlistRepository.observePlaylists().collectAsState(initial = emptyList())
        val scope = rememberCoroutineScope()
        var editor by remember { mutableStateOf<PlaylistSummary?>(null) }
        var creating by remember { mutableStateOf(false) }
        var deleting by remember { mutableStateOf<PlaylistSummary?>(null) }
        Column(Modifier.fillMaxSize().padding(ClearTuneDimensions.spacingMd)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onNavigate("back") }) { Text("Back") }
                Text("Playlists", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                Button(onClick = { creating = true }) { Text("New") }
            }
            if (playlists.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No playlists yet", style = MaterialTheme.typography.titleLarge)
                        Text("Create a playlist to organize your music")
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(ClearTuneDimensions.spacingSm)) {
                    items(playlists, key = { it.id.value }) { playlist ->
                        Card(Modifier.fillMaxWidth().clickable(onClickLabel = "Open ${playlist.name}") {
                            onNavigate(detailRoute(playlist.id))
                        }) {
                            Row(
                                Modifier.fillMaxWidth().padding(ClearTuneDimensions.spacingMd),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(playlist.name, style = MaterialTheme.typography.titleMedium)
                                    Text("${playlist.trackCount} tracks", style = MaterialTheme.typography.bodySmall)
                                }
                                TextButton(onClick = { editor = playlist }) { Text("Rename") }
                                TextButton(onClick = { deleting = playlist }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        }
        if (creating || editor != null) {
            PlaylistNameDialog(
                title = if (creating) "New playlist" else "Rename playlist",
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
                title = { Text("Delete ${playlist.name}?") },
                text = { Text("Music files will not be removed.") },
                confirmButton = { TextButton(onClick = {
                    scope.launch { dependencies.playlistRepository.apply(PlaylistCommand.Delete(playlist.id)) }
                    deleting = null
                }) { Text("Delete") } },
                dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun PlaylistDetailsScreen(
    details: PlaylistDetails?,
    titles: Map<TrackId, String>,
    dependencies: PlaylistsFeatureDependencies,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val trackIds = details?.items?.map { it.trackId }.orEmpty()
    val rows = details?.toPlaylistRows(titles).orEmpty()
    Column(Modifier.fillMaxSize().padding(ClearTuneDimensions.spacingMd)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(details?.name ?: "Playlist", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ClearTuneDimensions.spacingSm)) {
            Button(enabled = trackIds.isNotEmpty(), onClick = { scope.launch {
                dependencies.queueRepository?.apply(QueueCommand.Replace(trackIds))
                trackIds.firstOrNull()?.let { dependencies.playbackGateway.dispatch(PlaybackCommand.PlayTrack(it)) }
            } }) { Text("Play") }
            TextButton(enabled = trackIds.size > 1, onClick = { scope.launch {
                val shuffled = trackIds.shuffled()
                dependencies.queueRepository?.apply(QueueCommand.Replace(shuffled))
                shuffled.firstOrNull()?.let { dependencies.playbackGateway.dispatch(PlaybackCommand.PlayTrack(it)) }
            } }) { Text("Shuffle") }
        }
        if (details == null || details.items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("This playlist is empty") }
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                itemsIndexed(details.items, key = { _, item -> item.id.value }) { index, item ->
                    val row = rows[index]
                    Row(
                        Modifier.fillMaxWidth().semantics {
                            customActions = listOf(
                                CustomAccessibilityAction(row.addNextActionLabel) {
                                    scope.launch { dependencies.queueRepository?.apply(QueueCommand.AddNext(item.trackId)) }
                                    true
                                },
                                CustomAccessibilityAction(row.addLastActionLabel) {
                                    scope.launch { dependencies.queueRepository?.apply(QueueCommand.AddLast(item.trackId)) }
                                    true
                                },
                                CustomAccessibilityAction(row.removeActionLabel) {
                                    scope.launch { dependencies.playlistRepository.apply(
                                        PlaylistCommand.RemoveTrack(details.id, item.id),
                                    ) }
                                    true
                                },
                            )
                        }.padding(vertical = ClearTuneDimensions.spacingXs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${index + 1}", modifier = Modifier.padding(end = ClearTuneDimensions.spacingSm))
                        Text(row.title, modifier = Modifier.weight(1f))
                        TextButton(onClick = { scope.launch {
                            dependencies.queueRepository?.apply(QueueCommand.AddNext(item.trackId))
                        } }) { Text("Next") }
                        TextButton(onClick = { scope.launch {
                            dependencies.queueRepository?.apply(QueueCommand.AddLast(item.trackId))
                        } }) { Text("Last") }
                        TextButton(enabled = index > 0, onClick = { scope.launch {
                            dependencies.playlistRepository.apply(PlaylistCommand.MoveTrack(details.id, item.id, index - 1))
                        } }) { Text("Up") }
                        TextButton(enabled = index < details.items.lastIndex, onClick = { scope.launch {
                            dependencies.playlistRepository.apply(PlaylistCommand.MoveTrack(details.id, item.id, index + 1))
                        } }) { Text("Down") }
                        TextButton(onClick = { scope.launch {
                            dependencies.playlistRepository.apply(PlaylistCommand.RemoveTrack(details.id, item.id))
                        } }) { Text("Remove") }
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
        text = { OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(100) },
            label = { Text("Name") },
            singleLine = true,
            supportingText = { Text(if (duplicate) "A playlist already has that name" else "${name.length}/100") },
        ) },
        confirmButton = { TextButton(enabled = valid, onClick = { onConfirm(name) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
