package com.cleartune.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cleartune.core.model.Playlist

@Stable
internal class SongBatchSelection {
    var active by mutableStateOf(false)
        private set
    var ids by mutableStateOf(emptySet<String>())
        private set
    var busy by mutableStateOf(false)

    fun start(id: String) {
        if (busy) return
        active = true
        ids = setOf(id)
    }
    fun toggle(id: String) {
        if (active && !busy) ids = if (id in ids) ids - id else ids + id
    }
    fun retain(available: Set<String>) { ids = ids.intersect(available) }
    fun toggleAll(visible: Set<String>) {
        if (!busy) ids = if (visible.isNotEmpty() && ids.containsAll(visible)) ids - visible else ids + visible
    }
    fun clear() {
        if (busy) return
        active = false
        ids = emptySet()
    }

    companion object {
        val Saver = listSaver<SongBatchSelection, String>(
            save = { listOf(it.active.toString()) + it.ids },
            restore = { values -> SongBatchSelection().apply {
                active = values.first().toBoolean()
                ids = values.drop(1).toSet()
            } },
        )
    }
}

@Composable
internal fun SongSelectionHeader(selection: SongBatchSelection, visibleIds: Set<String>) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = selection::clear, enabled = !selection.busy) { Text(stringResource(R.string.cancel)) }
        Text(stringResource(R.string.selected_songs_count, selection.ids.size),
            modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        TextButton(onClick = { selection.toggleAll(visibleIds) }, enabled = visibleIds.isNotEmpty() && !selection.busy) {
            Text(stringResource(if (visibleIds.isNotEmpty() && selection.ids.containsAll(visibleIds))
                R.string.clear_selection else R.string.select_all))
        }
    }
}

@Composable
internal fun SongSelectionBottomBar(count: Int, busy: Boolean, onAdd: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 8.dp) {
        Button(onClick = onAdd, enabled = count > 0 && !busy,
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(stringResource(if (busy) R.string.batch_adding else R.string.batch_add_count, count))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BatchPlaylistSheet(
    playlists: List<Playlist>,
    count: Int,
    busy: Boolean,
    error: String?,
    onSelect: (String) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
    cover: @Composable (Playlist) -> Unit,
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { !busy })
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = sheetState,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !busy)) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp)) {
            Text(stringResource(R.string.batch_choose_playlist), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.selected_songs_count, count),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
            if (creating) {
                OutlinedTextField(value = name, onValueChange = { name = it }, enabled = !busy,
                    label = { Text(stringResource(R.string.batch_playlist_name)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                Row {
                    TextButton(onClick = { creating = false }, enabled = !busy) { Text(stringResource(R.string.cancel)) }
                    TextButton(onClick = { onCreate(name.trim()) }, enabled = name.isNotBlank() && !busy) {
                        Text(stringResource(R.string.batch_create_and_add))
                    }
                }
            } else {
                TextButton(onClick = { creating = true }, enabled = !busy) { Text(stringResource(R.string.batch_new_playlist)) }
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(playlists, key = { it.id }) { playlist ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { onSelect(playlist.id) }
                        .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        cover(playlist)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(R.string.song_count, playlist.songCount),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
