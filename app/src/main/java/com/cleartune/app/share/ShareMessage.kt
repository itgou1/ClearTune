package com.cleartune.app.share

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val messageDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** The same readable text is used in the preview, clipboard and receiving app. */
internal fun shareMessage(share: MusicShare, zone: ZoneId = ZoneId.systemDefault()): String {
    if (share.url.isBlank()) return ""
    val kind = when (share.kind) {
        ShareKind.SONG -> "歌曲"
        ShareKind.ALBUM -> "专辑"
        ShareKind.PLAYLIST -> "歌单"
        ShareKind.UNKNOWN -> "音乐"
    }
    val title = share.title.singleLine()
    return buildString {
        append("分享$kind")
        if (title.isNotEmpty() && title != "音乐分享") append("《$title》")
        val artist = share.artist.singleLine().takeUnless {
            it.isEmpty() || it in setOf("未知", "未知艺术家", "未知歌手") || it.equals("unknown artist", ignoreCase = true)
        }
        if (share.kind in setOf(ShareKind.SONG, ShareKind.ALBUM) && artist != null) {
            append("\n艺术家：$artist")
        }
        if (share.kind in setOf(ShareKind.ALBUM, ShareKind.PLAYLIST) && share.songCount > 0) {
            append("\n共 ${share.songCount} 首歌曲")
        }
        share.description.trim().takeIf(String::isNotEmpty)?.let { append("\n\n分享说明：$it") }
        append("\n\n收听链接：\n${share.url}")
        share.expiresAt?.takeIf { it > 0 }?.let {
            append("\n\n有效期至：${messageDateFormatter.format(Instant.ofEpochMilli(it).atZone(zone))}")
        }
    }
}

private fun String.singleLine() = trim().replace(Regex("\\s+"), " ")
