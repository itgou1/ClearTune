package com.cleartune.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleartune.app.library.MusicViewModel
import com.cleartune.app.library.PlaylistSongFilter
import com.cleartune.app.library.filterPlaylistSongCandidates
import com.cleartune.app.library.playlistSongCandidates
import com.cleartune.core.model.Playlist
import com.cleartune.core.model.Song

@Composable
internal fun PlaylistSongPicker(
    playlist: Playlist,
    songs: List<Song>,
    existingSongs: List<Song>,
    viewModel: MusicViewModel,
    onDismiss: () -> Unit,
) {
    val submission by viewModel.playlistSongAddState.collectAsStateWithLifecycle()
    var query by rememberSaveable(playlist.id) { mutableStateOf("") }
    var filter by rememberSaveable(playlist.id) { mutableStateOf(PlaylistSongFilter.ALL) }
    var selectedIds by rememberSaveable(playlist.id) { mutableStateOf(arrayListOf<String>()) }
    val candidates = remember(songs, existingSongs) {
        playlistSongCandidates(songs, existingSongs.map(Song::id).toSet())
    }
    val eligibleIds = remember(candidates) { candidates.map(Song::id).toSet() }
    val selection = selectedIds.filter { it in eligibleIds }.toSet()
    val visibleSongs = remember(candidates, filter, query) {
        filterPlaylistSongCandidates(candidates, filter, query)
    }
    val listState = rememberLazyListState()
    LaunchedEffect(filter, query) { listState.scrollToItem(0) }
    LaunchedEffect(submission.completed) { if (submission.completed) onDismiss() }

    Dialog(
        onDismissRequest = { if (!submission.isAdding) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = !submission.isAdding,
            dismissOnClickOutside = false,
        ),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding(),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                Row(
                    modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss, enabled = !submission.isAdding) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.add_songs), style = MaterialTheme.typography.titleLarge)
                        Text(
                            stringResource(R.string.picker_destination, playlist.name),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = onDismiss, enabled = !submission.isAdding) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            },
            bottomBar = {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.navigationBarsPadding()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        submission.errorMessage?.let {
                            Text(it, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error)
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.picker_selected, selection.size), style = MaterialTheme.typography.titleMedium)
                                TextButton(
                                    onClick = { selectedIds = arrayListOf() },
                                    enabled = selection.isNotEmpty() && !submission.isAdding,
                                    contentPadding = PaddingValues(0.dp),
                                ) { Text(stringResource(R.string.clear_action)) }
                            }
                            Button(
                                onClick = { viewModel.addPlaylistSongs(playlist.id, selection.toList()) },
                                enabled = selection.isNotEmpty() && !submission.isAdding,
                                modifier = Modifier.heightIn(min = 48.dp),
                                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp),
                            ) {
                                if (submission.isAdding) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(stringResource(if (submission.isAdding) R.string.picker_adding else R.string.picker_add_count, selection.size))
                            }
                        }
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    enabled = !submission.isAdding,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    placeholder = { Text(stringResource(R.string.picker_search_hint), style = MaterialTheme.typography.bodyMedium) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = if (query.isNotEmpty()) ({
                        IconButton(onClick = { query = "" }, enabled = !submission.isAdding) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.picker_clear_search))
                        }
                    }) else null,
                    singleLine = true,
                    shape = CircleShape,
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(PlaylistSongFilter.entries) { option ->
                        FilterChip(
                            selected = filter == option,
                            onClick = { filter = option },
                            enabled = !submission.isAdding,
                            shape = CircleShape,
                            label = {
                                Text(stringResource(when (option) {
                                    PlaylistSongFilter.ALL -> R.string.picker_all
                                    PlaylistSongFilter.FAVORITES -> R.string.picker_favorites
                                    PlaylistSongFilter.RECENT -> R.string.picker_recent
                                }))
                            },
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.picker_available, visibleSongs.size),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = { selectedIds = ArrayList(selection + visibleSongs.map(Song::id)) },
                        enabled = !submission.isAdding && visibleSongs.any { it.id !in selection },
                    ) { Text(stringResource(R.string.select_all)) }
                }
                if (visibleSongs.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(when {
                                candidates.isEmpty() -> R.string.picker_empty_available
                                query.isNotBlank() -> R.string.picker_no_matches
                                filter == PlaylistSongFilter.RECENT -> R.string.picker_empty_recent
                                filter == PlaylistSongFilter.FAVORITES -> R.string.picker_empty_favorites
                                else -> R.string.picker_empty_available
                            }),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(visibleSongs, key = Song::id) { song ->
                            val selected = song.id in selection
                            Row(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                    .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background)
                                    .toggleable(
                                        value = selected,
                                        enabled = !submission.isAdding,
                                        role = Role.Checkbox,
                                        onValueChange = {
                                            selectedIds = ArrayList(if (selected) selection - song.id else selection + song.id)
                                        },
                                    ).padding(horizontal = 10.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CoverArt(song.displayCoverArtId(), song.title, viewModel, Modifier.size(44.dp), fallbackSeed = song.id)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(song.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    song.displayArtistName()?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
                                Icon(
                                    if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                    contentDescription = null,
                                    modifier = Modifier.size(26.dp),
                                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Text(
                    stringResource(R.string.picker_selection_hint),
                    Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
