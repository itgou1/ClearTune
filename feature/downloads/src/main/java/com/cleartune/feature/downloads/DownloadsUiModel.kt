package com.cleartune.feature.downloads

import com.cleartune.core.model.DownloadId
import com.cleartune.core.model.DownloadState
import com.cleartune.core.model.DownloadSummary

data class DownloadUiItem(
    val id: DownloadId,
    val title: String,
    val status: String,
    val detail: String,
    val progress: Float?,
    val state: DownloadState,
)

data class DownloadUiGroup(val title: String, val items: List<DownloadUiItem>)

fun DownloadSummary.toUiItem(): DownloadUiItem {
    val progress = totalBytes?.takeIf { it > 0 }?.let { (bytesDownloaded.toDouble() / it).toFloat().coerceIn(0f, 1f) }
    val status = when (state) {
        DownloadState.QUEUED -> "等待下载"
        DownloadState.RUNNING -> "正在下载"
        DownloadState.PAUSED -> "已暂停"
        DownloadState.COMPLETED -> "已完成"
        DownloadState.UPDATE_AVAILABLE -> "远程文件已有更新"
        DownloadState.FAILED -> "下载失败"
        DownloadState.CANCELED -> "已取消"
    }
    val detail = totalBytes?.let { "${formatBytes(bytesDownloaded)} / ${formatBytes(it)}" }
        ?: formatBytes(bytesDownloaded)
    return DownloadUiItem(id, trackId.value, status, detail, progress, state)
}

fun groupDownloads(downloads: List<DownloadSummary>): List<DownloadUiGroup> {
    val items = downloads.map(DownloadSummary::toUiItem)
    val definitions = listOf(
        "进行中" to setOf(DownloadState.QUEUED, DownloadState.RUNNING),
        "需要处理" to setOf(DownloadState.PAUSED, DownloadState.FAILED, DownloadState.UPDATE_AVAILABLE),
        "已完成" to setOf(DownloadState.COMPLETED),
    )
    return definitions.mapNotNull { (title, states) ->
        items.filter { it.state in states }.takeIf(List<DownloadUiItem>::isNotEmpty)?.let { DownloadUiGroup(title, it) }
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> "%.1f KB".format(bytes.toDouble() / (1L shl 10))
    else -> "$bytes B"
}
