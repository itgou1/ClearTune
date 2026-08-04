package com.cleartune.feature.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cleartune.core.model.DownloadCommand
import com.cleartune.core.model.DownloadState
import com.cleartune.core.model.DownloadSummary

@Composable
fun DownloadsScreen(
    downloads: List<DownloadSummary>,
    onCommand: (DownloadCommand) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<com.cleartune.core.model.DownloadId?>(null) }
    val groups = groupDownloads(downloads)
    val completed = downloads.filter { it.state == DownloadState.COMPLETED }
    val offlineBytes = completed.sumOf { it.totalBytes ?: it.bytesDownloaded }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("已下载", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(
                "${completed.size} 首歌曲 · ${formatBytes(offlineBytes)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
        if (groups.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("暂无离线音乐", style = MaterialTheme.typography.titleMedium)
                        Text("从歌曲、专辑或文件夹菜单选择下载，之后可在无网络时播放。")
                    }
                }
            }
        }
        groups.forEach { group ->
            item { Text(group.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            items(group.items, key = { it.id.value }) { item ->
                DownloadRow(item) { command ->
                    if (command is DownloadCommand.Delete) pendingDelete = command.downloadId else onCommand(command)
                }
            }
        }
    }
    pendingDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除离线副本？") },
            text = { Text("歌曲资料、收藏和歌单不会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onCommand(DownloadCommand.Delete(id))
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun DownloadRow(item: DownloadUiItem, onCommand: (DownloadCommand) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.title, maxLines = 2, style = MaterialTheme.typography.titleSmall)
                    Text("${item.status} · ${item.detail}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DownloadActions(item, onCommand)
            }
            if (item.state == DownloadState.RUNNING) {
                Spacer(Modifier.height(8.dp))
                item.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                    ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun DownloadActions(item: DownloadUiItem, onCommand: (DownloadCommand) -> Unit) {
    when (item.state) {
        DownloadState.QUEUED, DownloadState.RUNNING -> {
            TextButton(onClick = { onCommand(DownloadCommand.Pause(item.id)) }) { Text("暂停") }
            TextButton(onClick = { onCommand(DownloadCommand.Cancel(item.id)) }) { Text("取消") }
        }
        DownloadState.PAUSED -> {
            TextButton(onClick = { onCommand(DownloadCommand.Resume(item.id)) }) { Text("继续") }
            TextButton(onClick = { onCommand(DownloadCommand.Cancel(item.id)) }) { Text("取消") }
        }
        DownloadState.FAILED, DownloadState.UPDATE_AVAILABLE ->
            TextButton(onClick = { onCommand(DownloadCommand.Retry(item.id)) }) { Text("重试") }
        DownloadState.COMPLETED ->
            TextButton(onClick = { onCommand(DownloadCommand.Delete(item.id)) }) { Text("删除") }
        DownloadState.CANCELED -> Unit
    }
}
