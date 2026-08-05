package com.cleartune.feature.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cleartune.core.model.PlaylistCommand
import com.cleartune.core.model.QueueCommand
import com.cleartune.core.model.TrackSummary
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryTrackActionSheet(
    track: TrackSummary,
    dependencies: LibraryFeatureDependencies,
    uiInputs: LibraryFeatureUiInputs,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val playlists by dependencies.playlistRepository.observePlaylists().collectAsState(initial = emptyList())
    val actionState by remember(track.id, uiInputs.observeTrackAction) { uiInputs.observeTrackAction(track.id) }
        .collectAsState(initial = LibraryTrackActionUi())
    var choosingPlaylist by remember(track.id) { mutableStateOf(false) }
    var showingDetails by remember(track.id) { mutableStateOf(false) }
    fun queue(command: QueueCommand) = scope.launch {
        dependencies.queueRepository?.apply(command)
        dependencies.onQueueChanged()
        onDismiss()
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(track.title)
            TextButton(onClick = { queue(QueueCommand.AddNext(track.id)) }) { Text("Play next") }
            TextButton(onClick = { queue(QueueCommand.AddLast(track.id)) }) { Text("Add to queue") }
            TextButton(onClick = { choosingPlaylist = !choosingPlaylist }) { Text("Add to playlist") }
            if (choosingPlaylist) {
                selectablePlaylists(playlists).forEach { playlist ->
                    TextButton(onClick = {
                        scope.launch {
                            dependencies.playlistRepository.apply(PlaylistCommand.AddTrack(playlist.id, track.id))
                            onDismiss()
                        }
                    }) { Text(playlist.name) }
                }
                if (playlists.isEmpty()) Text("Create a playlist first")
            }
            TextButton(onClick = { scope.launch { uiInputs.onToggleFavorite(track.id) } }) {
                Text(if (actionState.isFavorite) "Remove favorite" else "Favorite")
            }
            TextButton(
                enabled = actionState.canDownload,
                onClick = { scope.launch { uiInputs.onToggleDownload(track.id) } },
            ) { Text(if (actionState.isDownloaded) "Remove download" else "Download") }
            if (!actionState.canDownload) Text(actionState.downloadUnavailableReason)
            TextButton(onClick = { showingDetails = !showingDetails }) { Text("Details") }
            if (showingDetails) {
                HorizontalDivider()
                Text(track.artistNames.joinToString().ifBlank { "Unknown artist" })
                track.albumTitle?.let { Text(it) }
                track.durationMs?.let { Text("${it / 1_000} seconds") }
            }
        }
    }
}
