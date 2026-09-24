package com.cleartune.app.metadata

import android.content.Context
import com.cleartune.app.auth.AccountSession
import com.cleartune.app.artwork.ArtworkCache
import com.cleartune.app.library.MusicRepository
import com.cleartune.core.datastore.MusicTagSettingsStore
import com.cleartune.core.database.toModel
import com.cleartune.core.model.*
import com.cleartune.core.network.*
import com.cleartune.core.player.AudioFileRevision
import com.cleartune.core.player.PlaybackService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

data class PendingTagWrite(val path: String, val changes: Map<MusicTagField, String>, val baseUrl: String,
    val writeAccepted: Boolean = false, val approvedCoverHash: String? = null, val writeFailure: String? = null,
    val fileVerified: Boolean = false, val expectedCoverHash: String? = null)

data class MusicTagCoverReview(val actual: String, val expectedUrl: String, val versionLimited: Boolean,
    val verifiedFields: Map<MusicTagField, String> = emptyMap())
class MusicTagCoverReviewRequired(val snapshot: MusicTagSnapshot, val review: MusicTagCoverReview) : Exception()

class MusicTagRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val session: AccountSession,
    private val apiFactory: OpenSubsonicApiFactory,
    private val music: MusicRepository,
) {
    private val settingsStore = MusicTagSettingsStore(context)
    private val journal = context.getSharedPreferences("music_tag_pending", Context.MODE_PRIVATE)
    private val suspendedPlayback = mutableMapOf<String, () -> Unit>()
    val active: Boolean get() = session.active
    private fun checkSession() = check(active) { "登录状态已变化，请重新打开音乐标签" }
    suspend fun settings() = settingsStore.read(session.accountKey)
    suspend fun latestSong(songId: String): Song? = session.database.mediaDao().song(songId)?.toModel()
    suspend fun saveSettings(settings: MusicTagSettings) { checkSession(); settingsStore.save(session.accountKey, settings) }
    suspend fun disconnect() { checkSession(); settingsStore.clear(session.accountKey) }
    suspend fun client(settings: MusicTagSettings): MusicTagClient {
        checkSession()
        require(settings.configured) { "请先在设置中配置 Music Tag 服务" }
        return MusicTagClient(settings).also { it.login(); checkSession() }
    }

    suspend fun songPath(song: Song, settings: MusicTagSettings): String {
        val path = sourcePath(song, settings)
        return MusicTagPath.resolve(path, settings.sourceRoot, settings.targetRoot)
    }

    suspend fun sourcePath(song: Song, settings: MusicTagSettings): String {
        val dto = remoteSong(song.id)
        val path = dto.path.orEmpty()
        if (session.profile.serverType.equals("navidrome", ignoreCase = true) && !path.startsWith('/')) {
            checkSession()
            val actual = NavidromeFileClient(checkNotNull(session.credentials())).songPath(song.id, settings.sourceRoot)
            checkSession()
            return actual
        }
        return path.takeIf(String::isNotBlank) ?: error("服务器未提供歌曲文件路径，暂不能应用标签")
    }

    fun cachedArtwork(song: Song): String? = song.coverArtId?.let {
        ArtworkCache.localFile(context, session.accountKey, it)?.absolutePath
    }

    private suspend fun remoteSong(id: String): SongDto {
        checkSession()
        val api = apiFactory.authorized(checkNotNull(session.credentials()))
        val response = TagLibraryClient(api).song(id)
        checkSession()
        return response
    }

    fun pending(songId: String): PendingTagWrite? {
        val raw = journal.getString("${session.accountKey}:$songId", null) ?: return null
        val json = Json.parseToJsonElement(raw).jsonObject
        val changes = json.getValue("changes").jsonObject.mapKeys { MusicTagField.valueOf(it.key) }
            .mapValues { it.value.jsonPrimitive.content }
        return PendingTagWrite(json.getValue("path").jsonPrimitive.content, changes, json.getValue("baseUrl").jsonPrimitive.content,
            (json["writeAccepted"] as? JsonPrimitive)?.booleanOrNull ?: false,
            (json["approvedCoverHash"] as? JsonPrimitive)?.contentOrNull,
            (json["writeFailure"] as? JsonPrimitive)?.contentOrNull,
            (json["fileVerified"] as? JsonPrimitive)?.booleanOrNull ?: false,
            (json["expectedCoverHash"] as? JsonPrimitive)?.contentOrNull)
    }

    private fun expectedCoverFile(songId: String, hash: String): java.io.File {
        require(hash.matches(Regex("[a-f0-9]{64}")))
        return java.io.File(context.filesDir,
            "music_tag_covers/${musicTagImageHash("${session.accountKey}:$songId".toByteArray())}-$hash.image")
    }

    private suspend fun freezeCover(songId: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val file = expectedCoverFile(songId, musicTagImageHash(bytes))
        check(file.parentFile?.mkdirs() == true || file.parentFile?.isDirectory == true) { "无法保存封面核对数据，尚未提交保存" }
        val atomic = android.util.AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
    }

    private suspend fun rememberWrite(songId: String, value: PendingTagWrite?) = withContext(Dispatchers.IO) {
        val key = "${session.accountKey}:$songId"
        val removedCoverHash = if (value == null) pending(songId)?.expectedCoverHash else null
        val edit = journal.edit()
        if (value == null) edit.remove(key) else edit.putString(key, buildJsonObject {
            put("path", value.path); put("baseUrl", value.baseUrl)
            put("writeAccepted", value.writeAccepted)
            value.approvedCoverHash?.let { put("approvedCoverHash", it) }
            value.writeFailure?.let { put("writeFailure", it) }
            put("fileVerified", value.fileVerified)
            value.expectedCoverHash?.let { put("expectedCoverHash", it) }
            put("changes", buildJsonObject { value.changes.forEach { (field, text) -> put(field.name, text) } })
        }.toString())
        check(edit.commit()) { "无法保存操作状态，请稍后重试" }
        removedCoverHash?.let { android.util.AtomicFile(expectedCoverFile(songId, it)).delete() }
    }

    suspend fun apply(song: Song, settings: MusicTagSettings, client: MusicTagClient,
        snapshot: MusicTagSnapshot, changes: Map<MusicTagField, String>): MusicTagSnapshot {
        checkSession()
        check(pending(song.id) == null) { "上次保存结果待确认，请先检查保存结果" }
        validateMusicTagChanges(snapshot.path, changes)
        require(songPath(song, settings) == snapshot.path) { "歌曲文件路径已变化，请重新打开" }
        // Preparation is read-only. A failed download must not create an ambiguous write journal or stop playback.
        val prepared = client.prepareWrite(snapshot, changes)
        checkSession()
        val latest = client.read(snapshot.path)
        check(latest.values == snapshot.values) { "文件标签已被其他操作修改，请重新读取后刮削" }
        // Refuse to race a partially downloaded file; it can be completed or cancelled independently.
        val download = session.database.downloadDao().forSong(song.id)
        check(download == null || download.state == "COMPLETED") { "请先完成或取消这首歌曲的未完成下载，再应用标签" }
        checkSession()
        prepared.coverBytes?.let { freezeCover(song.id, it) }
        checkSession()
        rememberWrite(song.id, PendingTagWrite(snapshot.path, changes, normalizeMusicTagAddress(settings.baseUrl, settings.allowHttp),
            expectedCoverHash = prepared.coverHash))
        suspendedPlayback[song.id] = PlaybackService.suspendForTagWrite()
        try {
            withContext(Dispatchers.IO) { AudioFileRevision.bump(context, session.accountKey, song.id) }
            checkSession()
            try {
                client.write(prepared)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (error is MusicTagException && error.endpoint == "apimt/update_id3/" &&
                    (error.httpStatus in setOf(401, 403) || (error.httpStatus == 400 && error.serviceCode == "40000"))) {
                    // Explicitly rejected requests did not write a file. Unknown outcomes retain the journal.
                    rememberWrite(song.id, null)
                    resumePlayback(song.id)
                } else {
                    val failure = when (error) {
                        is MusicTagException -> error.httpStatus?.let { "写入接口返回 HTTP $it" }
                            ?: error.message ?: "写入接口未返回成功确认"
                        is java.io.InterruptedIOException -> "写入请求超时，未收到成功确认"
                        else -> "写入请求中断，未收到成功确认"
                    }
                    rememberWrite(song.id, checkNotNull(pending(song.id)).copy(writeFailure = failure))
                }
                throw error
            }
            rememberWrite(song.id, checkNotNull(pending(song.id)).copy(writeAccepted = true))
            val result = verify(song, settings, client)
            return result
        } finally {
            if (pending(song.id) == null) resumePlayback(song.id)
        }
    }

    private suspend fun resumePlayback(songId: String) = withContext(NonCancellable + Dispatchers.Main.immediate) {
        suspendedPlayback.remove(songId)?.let { if (active) it() }
    }

    suspend fun verify(song: Song, settings: MusicTagSettings, client: MusicTagClient,
        approvedCover: MusicTagCoverReview? = null): MusicTagSnapshot {
        checkSession()
        val pending = checkNotNull(pending(song.id)) { "没有待确认的保存" }
        check(pending.baseUrl == normalizeMusicTagAddress(settings.baseUrl, settings.allowHttp)) { "请恢复上次保存所用的 Music Tag 服务地址" }
        val readBack = client.read(pending.path)
        val mismatch = pending.changes.filter { (field, value) ->
            field != MusicTagField.COVER && readBack.values[field]?.trim()?.replace("\r\n", "\n") != value.trim().replace("\r\n", "\n")
        }.keys
        check(mismatch.isEmpty()) {
            val originalFailure = pending.writeFailure?.let { "上次保存：$it。" }.orEmpty()
            "${originalFailure}读回与提交结果不一致：${mismatch.joinToString { it.label }}。请在 Music Tag 中检查文件后重新读取"
        }
        pending.changes[MusicTagField.COVER]?.let { expected ->
            val actual = readBack.values[MusicTagField.COVER].orEmpty()
            val frozenHash = pending.expectedCoverHash
            val expectedPreview = if (frozenHash == null) expected else withContext(Dispatchers.IO) {
                val file = expectedCoverFile(song.id, frozenHash)
                check(file.isFile && musicTagImageHash(file.readBytes()) == frozenHash) { "封面核对数据已丢失，请在 Music Tag 中核实保存结果后重新读取" }
                file.absolutePath
            }
            if (coverReviewApproves(approvedCover, actual, expectedPreview)) {
                rememberWrite(song.id, pending.copy(approvedCoverHash = tagCoverHash(actual)))
            } else if (pending.approvedCoverHash == null || pending.approvedCoverHash != tagCoverHash(actual)) {
                val matched = if (frozenHash != null) client.artworkMatchesHash(pending.path, frozenHash)
                    else client.artworkMatches(pending.path, expected)
                if (matched != true) throw MusicTagCoverReviewRequired(readBack, MusicTagCoverReview(actual, expectedPreview, matched == null,
                    pending.changes.filterKeys { it != MusicTagField.COVER }.mapValues { readBack.values[it.key].orEmpty() }))
            }
        }
        checkSession()
        rememberWrite(song.id, checkNotNull(pending(song.id)).copy(fileVerified = true))
        resumePlayback(song.id)
        return readBack
    }

    /** The file may be saved while the library still returns stale tags. Never repeat the write here. */
    suspend fun sync(song: Song): Boolean {
        checkSession()
        val pending = pending(song.id) ?: return true
        check(pending.fileVerified) { "请先确认文件保存结果" }
        val credentials = checkNotNull(session.credentials())
        val api = apiFactory.authorized(credentials)
        val library = TagLibraryClient(api)
        val scanStarted = try { library.startScan() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
        if (pending.changes.keys.any { it == MusicTagField.COVER || it == MusicTagField.LYRICS } && !scanStarted) return false
        // Large libraries can take longer than the old 10-second scan window.
        repeat(60) { attempt ->
            if (attempt > 0) delay(2_000)
            val scanning = try { library.scanning() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
            if (scanning == true || (MusicTagField.COVER in pending.changes && (scanning != false || attempt == 0))) return@repeat
            val current = remoteSong(song.id)
            val expected = pending.changes.filterKeys { it in setOf(MusicTagField.TITLE, MusicTagField.ARTIST, MusicTagField.ALBUM, MusicTagField.YEAR, MusicTagField.GENRE) }
            val values = mapOf(MusicTagField.TITLE to current.title, MusicTagField.ARTIST to current.artist,
                MusicTagField.ALBUM to current.album, MusicTagField.YEAR to current.year?.toString(), MusicTagField.GENRE to current.genre)
            if (expected.all { (field, value) -> values[field]?.trim() == value.trim() }) {
                if (MusicTagField.LYRICS in pending.changes) {
                    val result = LibraryRemoteDataSource(api).lyrics(song.copy(title = current.title, artistName = current.artist))
                    // Lyrics use another endpoint and may update later than getSong.
                    if (result !is RemoteResult.Success ||
                        normalizedTagLyrics(result.value.lines.joinToString("\n") { it.text }) !=
                        normalizedTagLyrics(pending.changes.getValue(MusicTagField.LYRICS))) return@repeat
                }
                checkSession()
                val previous = latestSong(song.id) ?: song
                music.refreshTagSong(previous, previous.copy(title = current.title,
                    artistId = current.artistId, artistName = current.artist.ifBlank { "未知艺术家" },
                    albumId = current.albumId, albumName = current.album.ifBlank { "未知专辑" },
                    year = current.year, genre = current.genre, coverArtId = current.coverArt))
                session.database.lyricsDao().invalidate(credentials.baseUrl.trimEnd('/'), credentials.username, song.id)
                ArtworkCache.invalidateAfterTagEdit(context, session.accountKey, setOfNotNull(song.coverArtId, current.coverArt))
                rememberWrite(song.id, null)
                return true
            }
        }
        return false
    }

    suspend fun acknowledgeMismatch(songId: String) { checkSession(); rememberWrite(songId, null); resumePlayback(songId) }
}

internal fun coverReviewApproves(review: MusicTagCoverReview?, actual: String, expectedUrl: String): Boolean =
    review != null && review.actual == actual && review.expectedUrl == expectedUrl &&
        actual.startsWith("data:image/") && actual.substringAfter(";base64,", "").isNotBlank()

internal fun tagCoverHash(actual: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(actual.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal fun normalizedTagLyrics(value: String): List<String> = value.lines().map { line ->
    line.replace(Regex("\\[\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?]"), "").trim()
}.filter { it.isNotBlank() && !it.matches(Regex("\\[(?:ar|al|ti|by|offset|re|ve|length):.*]", RegexOption.IGNORE_CASE)) }
