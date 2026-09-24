package com.cleartune.app.library

import com.cleartune.core.model.Lyrics
import com.cleartune.core.network.RemoteResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.flow

internal const val LYRICS_MAX_AGE_MS = 24L * 60 * 60 * 1_000

internal fun lyricsRefreshFeedback(forceRefresh: Boolean, failed: Boolean, before: Lyrics?, after: Lyrics?): String? {
    if (!forceRefresh) return null
    if (failed) return if (after?.lines.isNullOrEmpty()) "刷新失败，请联网后重试" else "刷新失败，已保留本地歌词"
    if (after?.lines.isNullOrEmpty()) return "服务器暂无歌词"
    return if (before == after) "Navidrome 返回的歌词未变化" else "已从 Navidrome 重新读取歌词"
}

/** Emit saved lyrics immediately; an empty result must never permanently suppress a retry. */
internal fun lyricsResults(
    cached: Lyrics?,
    updatedAt: Long?,
    forceRefresh: Boolean,
    now: Long = System.currentTimeMillis(),
    fetch: suspend () -> RemoteResult<Lyrics>,
    save: suspend (Lyrics) -> Unit,
) = flow {
    if (cached != null) emit(RemoteResult.Success(cached))
    val age = updatedAt?.let { now - it }
    if (!forceRefresh && !cached?.lines.isNullOrEmpty() && age != null && age in 0 until LYRICS_MAX_AGE_MS) {
        return@flow
    }
    val result = fetch()
    currentCoroutineContext().ensureActive()
    if (result is RemoteResult.Success) save(result.value)
    emit(result)
}
