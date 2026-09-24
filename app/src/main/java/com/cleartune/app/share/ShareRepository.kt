package com.cleartune.app.share

import android.content.Context
import com.cleartune.app.auth.AccountSession
import com.cleartune.core.model.ClearTuneError
import com.cleartune.core.network.LibraryRemoteDataSource
import com.cleartune.core.network.OpenSubsonicApiFactory
import com.cleartune.core.network.RemoteResult
import com.cleartune.core.network.ShareDto
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.OffsetDateTime
import javax.inject.Inject
import org.json.JSONObject

enum class ShareKind { SONG, ALBUM, PLAYLIST, UNKNOWN }

data class ShareTarget(
    val kind: ShareKind,
    val id: String,
    val title: String,
    val subtitle: String,
    val coverArtId: String? = null,
    val songCount: Int = 0,
)

data class MusicShare(
    val id: String,
    val url: String,
    val title: String,
    val description: String,
    val kind: ShareKind,
    val coverArtId: String?,
    val songCount: Int,
    val createdAt: Long?,
    val expiresAt: Long?,
    val visitCount: Int,
    val artist: String = "",
) {
    val expired: Boolean get() = expiresAt?.let { it <= System.currentTimeMillis() } == true
}

class ShareRepository @Inject constructor(
    private val session: AccountSession,
    private val apiFactory: OpenSubsonicApiFactory,
    @param:ApplicationContext private val context: Context,
) {
    private val preferences by lazy { context.getSharedPreferences("music_shares", Context.MODE_PRIVATE) }

    private suspend fun remote(): LibraryRemoteDataSource? =
        session.credentials()?.let { LibraryRemoteDataSource(apiFactory.authorized(it)) }

    suspend fun list(): RemoteResult<List<MusicShare>> {
        val remote = remote() ?: return RemoteResult.Failure(ClearTuneError.Authentication())
        return when (val result = remote.shares()) {
            is RemoteResult.Success -> {
                if (session.credentials() == null) return RemoteResult.Failure(ClearTuneError.Authentication())
                RemoteResult.Success(result.value
                    .filter { it.username.equals(session.profile.username, ignoreCase = true) ||
                        (it.username.isBlank() && preferences.contains(key(it.id))) }
                    .map(::toModel)
                    .sortedByDescending { it.createdAt ?: 0L })
            }
            is RemoteResult.Failure -> result
        }
    }

    suspend fun create(target: ShareTarget, description: String, days: Int): RemoteResult<MusicShare> {
        val remote = remote() ?: return RemoteResult.Failure(ClearTuneError.Authentication())
        val ids = if (target.kind == ShareKind.PLAYLIST) {
            when (val result = remote.playlist(target.id)) {
                is RemoteResult.Failure -> return result
                is RemoteResult.Success -> {
                    val songs = result.value.songs
                    if (songs.isEmpty()) return RemoteResult.Failure(ClearTuneError.Server(userMessage = "歌单还没有歌曲，添加后再分享"))
                    if (songs.size != result.value.playlist.songCount) {
                        return RemoteResult.Failure(ClearTuneError.Server(userMessage = "未能读取完整歌单，请重试"))
                    }
                    songs.map { it.id }
                }
            }
        } else listOf(target.id)
        if (session.credentials() == null) return RemoteResult.Failure(ClearTuneError.Authentication())
        val expires = System.currentTimeMillis() + days * 86_400_000L
        return when (val result = remote.createShare(ids, description.trim().takeIf(String::isNotEmpty), expires)) {
            is RemoteResult.Failure -> result
            is RemoteResult.Success -> {
                if (session.credentials() == null) return RemoteResult.Failure(ClearTuneError.Authentication())
                if (!isPublicUrl(result.value.url)) return RemoteResult.Failure(ClearTuneError.Server(userMessage = "服务器未返回有效的分享链接"))
                val snapshot = if (target.kind == ShareKind.PLAYLIST) {
                    target.copy(songCount = ids.size)
                } else target
                remember(result.value.id, snapshot)
                RemoteResult.Success(toModel(result.value))
            }
        }
    }

    suspend fun update(id: String, description: String, days: Int): RemoteResult<Unit> {
        val remote = remote() ?: return RemoteResult.Failure(ClearTuneError.Authentication())
        val expires = System.currentTimeMillis() + days * 86_400_000L
        return remote.updateShare(id, description.trim(), expires)
    }

    suspend fun delete(id: String): RemoteResult<Unit> {
        val remote = remote() ?: return RemoteResult.Failure(ClearTuneError.Authentication())
        return when (val result = remote.deleteShare(id)) {
            is RemoteResult.Failure -> result
            is RemoteResult.Success -> {
                preferences.edit().remove(key(id)).apply()
                result
            }
        }
    }

    private fun remember(id: String, target: ShareTarget) {
        val value = JSONObject()
            .put("kind", target.kind.name)
            .put("title", target.title)
            .put("coverArtId", target.coverArtId)
            .put("songCount", target.songCount)
            .put("artist", target.subtitle.takeIf { target.kind == ShareKind.SONG || target.kind == ShareKind.ALBUM }.orEmpty())
        preferences.edit().putString(key(id), value.toString()).apply()
    }

    private fun key(id: String) = "${session.accountKey}:$id"

    private fun toModel(share: ShareDto): MusicShare {
        val metadata = preferences.getString(key(share.id), null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        // The standard response does not identify the original resource type.
        // A one-entry album share must not be presented as a song.
        val kind = metadata?.optString("kind")?.let { runCatching { ShareKind.valueOf(it) }.getOrNull() }
            ?: ShareKind.UNKNOWN
        val fallbackTitle = share.entry.firstOrNull()?.let { entry ->
            if (share.entry.size == 1) entry.title.ifBlank { entry.album } else "音乐分享"
        }.orEmpty().ifBlank { "音乐分享" }
        return MusicShare(
            id = share.id,
            url = share.url.takeIf(::isPublicUrl).orEmpty(),
            title = metadata?.optString("title")?.takeIf(String::isNotBlank) ?: fallbackTitle,
            description = share.description,
            kind = kind,
            coverArtId = metadata?.optString("coverArtId")?.takeIf(String::isNotBlank)
                ?: share.entry.firstOrNull()?.coverArt,
            songCount = metadata?.optInt("songCount")?.takeIf { it > 0 }
                ?: share.entry.size.takeIf { kind != ShareKind.UNKNOWN } ?: 0,
            createdAt = parseTimestamp(share.created),
            expiresAt = parseTimestamp(share.expires),
            visitCount = share.visitCount,
            artist = metadata?.optString("artist")?.takeIf(String::isNotBlank)
                ?: share.entry.map { it.artist.trim() }.distinct().singleOrNull().orEmpty(),
        )
    }
}

private fun parseTimestamp(value: String?): Long? = value?.let { raw ->
    runCatching { Instant.parse(raw).toEpochMilli() }
        .recoverCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }
        .getOrNull()
}

private fun isPublicUrl(value: String): Boolean = runCatching {
    val uri = android.net.Uri.parse(value)
    (uri.scheme == "https" || uri.scheme == "http") && !uri.host.isNullOrBlank() &&
        !uri.queryParameterNames.any { it in setOf("u", "t", "s", "p") }
}.getOrDefault(false)
