package com.cleartune.app.download

import androidx.work.WorkInfo
import com.cleartune.core.database.DownloadEntity

internal data class DownloadStatus(val state: String, val reason: String?)

internal fun downloadStatus(
    row: DownloadEntity, work: WorkInfo.State?, attempts: Int, error: String?,
    connected: Boolean, unmetered: Boolean, wifiOnly: Boolean, now: Long,
): DownloadStatus? {
    if (row.state !in setOf("QUEUED", "DOWNLOADING")) return null
    val status = when (work) {
        null -> if (now - row.updatedAt < 30_000) return null else
            DownloadStatus("FAILED", "后台任务不存在，请点击继续重新下载")
        WorkInfo.State.FAILED -> DownloadStatus("FAILED", error ?: "后台下载任务失败，请点击继续重试")
        WorkInfo.State.CANCELLED -> DownloadStatus("PAUSED", "后台任务已取消，可点击继续下载")
        WorkInfo.State.SUCCEEDED -> DownloadStatus("FAILED", "下载任务已结束，但没有有效完成记录，请重试")
        WorkInfo.State.RUNNING -> DownloadStatus("DOWNLOADING", row.failureReason.takeIf { row.state == "DOWNLOADING" })
        WorkInfo.State.BLOCKED -> DownloadStatus("QUEUED", "等待前置任务完成")
        WorkInfo.State.ENQUEUED -> DownloadStatus("QUEUED", when {
            !connected -> "等待网络连接"
            wifiOnly && !unmetered -> "等待非计费网络，请检查 Wi-Fi 是否被标记为计费"
            attempts > 0 -> "下载中断，等待自动重试（${attempts.coerceAtMost(2)}/2）"
            else -> "等待系统调度"
        })
    }
    return status.takeUnless { it.state == row.state && it.reason == row.failureReason }
}
