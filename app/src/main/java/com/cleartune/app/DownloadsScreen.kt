package com.cleartune.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cleartune.app.download.DownloadViewModel
import com.cleartune.core.model.DownloadItem
import com.cleartune.core.model.DownloadState
import com.cleartune.core.model.Song

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadsScreen(
    downloads: List<DownloadItem>,
    songs: List<Song>,
    viewModel: DownloadViewModel,
    onBack: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
) {
    val songsById = songs.associateBy(Song::id)
    Scaffold(
        topBar = {
            ClearTuneTopAppBar(title = stringResource(R.string.downloads), onBack = onBack)
        },
    ) { padding ->
        if (downloads.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.no_downloads), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(modifier = Modifier.padding(padding)) {
                items(downloads, key = DownloadItem::requestId) { item ->
                    val song = songsById[item.songId]
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(
                            song?.title ?: item.songId,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "${item.state.label()} · ${formatBytes(item.bytesDownloaded)}" +
                                (item.totalBytes?.let { " / ${formatBytes(it)}" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (item.state in setOf(DownloadState.QUEUED, DownloadState.DOWNLOADING)) {
                            LinearProgressIndicator(
                                progress = { item.progress.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                            )
                        }
                        item.failureReason?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            when (item.state) {
                                DownloadState.QUEUED, DownloadState.DOWNLOADING -> {
                                    TextButton(onClick = { viewModel.pause(item) }) {
                                        Text(stringResource(R.string.download_pause))
                                    }
                                }
                                DownloadState.PAUSED, DownloadState.FAILED -> {
                                    song?.let {
                                        TextButton(onClick = { viewModel.retry(item, it) }) {
                                            Text(stringResource(R.string.download_continue))
                                        }
                                    }
                                }
                                DownloadState.COMPLETED -> {
                                    song?.let {
                                        TextButton(onClick = { onPlay(listOf(it), 0) }) {
                                            Text(stringResource(R.string.play_action))
                                        }
                                    }
                                }
                            }
                            TextButton(onClick = { viewModel.delete(item) }) {
                                Text(stringResource(R.string.delete_local_file))
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OfflineMusicScreen(
    downloads: List<DownloadItem>,
    songs: List<Song>,
    onBack: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
) {
    val completedIds = downloads
        .filter { it.state == DownloadState.COMPLETED }
        .map(DownloadItem::songId)
        .toSet()
    val offlineSongs = songs.filter { it.id in completedIds }
    Scaffold(
        topBar = {
            ClearTuneTopAppBar(title = stringResource(R.string.offline_music), onBack = onBack)
        },
    ) { padding ->
        if (offlineSongs.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.no_offline_music), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(modifier = Modifier.padding(padding)) {
                items(offlineSongs, key = Song::id) { song ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPlay(offlineSongs, offlineSongs.indexOf(song)) }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ClearTuneIconTile(Icons.Rounded.MusicNote)
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 14.dp),
                        ) {
                            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            Text(
                                song.artistName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(formatDurationLabel(song.durationSeconds), style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider(modifier = Modifier.padding(start = 58.dp))
                }
            }
        }
    }
}

@Composable
private fun DownloadState.label(): String = stringResource(when (this) {
    DownloadState.QUEUED -> R.string.download_queued
    DownloadState.DOWNLOADING -> R.string.download_downloading
    DownloadState.PAUSED -> R.string.download_paused
    DownloadState.FAILED -> R.string.download_failed
    DownloadState.COMPLETED -> R.string.download_completed
})

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024 -> "%.1f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}

private fun formatDurationLabel(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)
