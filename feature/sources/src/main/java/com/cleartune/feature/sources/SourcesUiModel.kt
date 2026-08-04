package com.cleartune.feature.sources

import com.cleartune.core.model.MusicSource
import com.cleartune.core.model.SourceId
import com.cleartune.core.model.SourceType
import java.net.URI

data class SourceUiItem(
    val id: SourceId,
    val name: String,
    val location: String,
    val status: String,
    val local: Boolean,
    val enabled: Boolean,
    val insecure: Boolean,
)

fun MusicSource.toUiItem(nowEpochMs: Long = System.currentTimeMillis()): SourceUiItem {
    val local = type == SourceType.LOCAL
    val insecure = type == SourceType.WEBDAV && allowCleartext
    val lastSync = lastSyncedAtEpochMs
    val location = if (local) "此设备" else runCatching {
        URI(baseUrl.orEmpty()).host
    }.getOrNull().orEmpty().ifBlank { "WebDAV 服务器" }
    val status = when {
        !enabled -> "已停用"
        insecure -> "HTTP 未加密"
        lastSync == null -> if (local) "等待扫描" else "尚未同步"
        else -> formatSyncAge((nowEpochMs - lastSync).coerceAtLeast(0))
    }
    return SourceUiItem(id, name, location, status, local, enabled, insecure)
}

private fun formatSyncAge(ageMs: Long): String = when {
    ageMs < 60_000 -> "刚刚同步"
    ageMs < 3_600_000 -> "${ageMs / 60_000} 分钟前同步"
    ageMs < 86_400_000 -> "${ageMs / 3_600_000} 小时前同步"
    else -> "${ageMs / 86_400_000} 天前同步"
}

data class WebDavFormState(
    val name: String = "",
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val passwordVisible: Boolean = false,
    val allowCleartext: Boolean = false,
    val testing: Boolean = false,
    val connectionResult: String? = null,
    val error: String? = null,
)
