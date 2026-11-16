package com.cleartune.app.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.cleartune.core.database.DatabaseFactory
import com.cleartune.core.database.toModel
import com.cleartune.core.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal fun downloadFileName(artist: String, title: String, suffix: String?): String {
    fun clean(value: String) = value.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(70).ifBlank { "未知" }
    val extension = suffix?.takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) } ?: "audio"
    return "${clean(artist)} - ${clean(title)}.$extension"
}

internal fun availableDownloadName(name: String, exists: (String) -> Boolean): String {
    val stem = name.substringBeforeLast('.')
    val extension = name.substringAfterLast('.')
    var candidate = name
    var number = 2
    while (exists(candidate)) candidate = "$stem (${number++}).$extension"
    return candidate
}

data class DeviceDownload(val id: String, val account: String, val song: Song, val uri: String,
    val bytes: Long, val name: String, val legacy: Boolean = false, val complete: Boolean = true,
    val available: Boolean = true, val needsUpdate: Boolean = false, val digest: String = "", val revision: Long = 0)

/** Durable device index, deliberately independent of account database lifetime. */
class DeviceDownloads(private val context: Context) {
    private val index = context.getSharedPreferences("device_downloads_v1", Context.MODE_PRIVATE)
    companion object { private val operations = Mutex() }

    fun readable(uri: String?, expected: Long? = null): Boolean = runCatching {
        if (uri == null) return false
        context.contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r")?.use {
            val size = it.length
            size != 0L && (expected == null || expected <= 0 || size < 0 || size == expected)
        } == true
    }.getOrDefault(false)

    private fun decode(id: String, raw: String): DeviceDownload {
        val j = JSONObject(raw)
        return DeviceDownload(id, j.getString("account"), Song(id = j.getString("songId"),
            title = j.getString("title"), artistName = j.optString("artist"), albumName = j.optString("album"),
            suffix = j.optString("suffix"), durationSeconds = j.optLong("duration")),
            j.getString("uri"), j.getLong("bytes"), j.getString("name"), digest = j.optString("digest"), revision = j.optLong("revision"))
    }
    private fun entries() = index.all.mapNotNull { (id, value) ->
        if (id.startsWith("pending:")) null else runCatching { decode(id, value as String) }.getOrNull()
    }
    private fun save(item: DeviceDownload) {
        val j = JSONObject().put("account", item.account).put("songId", item.song.id)
            .put("title", item.song.title).put("artist", item.song.artistName).put("album", item.song.albumName)
            .put("suffix", item.song.suffix).put("duration", item.song.durationSeconds)
            .put("uri", item.uri).put("bytes", item.bytes).put("name", item.name).put("digest", item.digest).put("revision", item.revision)
        check(index.edit().putString(item.id, j.toString()).remove("pending:${item.id}").commit()) { "无法保存下载记录" }
    }
    suspend fun indexed(id: String): DeviceDownload? = withContext(Dispatchers.IO) { entries().firstOrNull { it.id == id } }
    suspend fun find(account: String, songId: String): DeviceDownload? = playbackFiles(account, setOf(songId))[songId]
    suspend fun playbackFiles(account: String, songIds: Set<String>): Map<String, DeviceDownload> = withContext(Dispatchers.IO) {
        entries().filter { it.account == account && it.song.id in songIds &&
            it.revision == com.cleartune.core.player.AudioFileRevision.get(context, account, it.song.id) && readable(it.uri, it.bytes) }
            .associateBy { it.song.id }
    }

    suspend fun list(): List<DeviceDownload> = withContext(Dispatchers.IO) {
        val result = entries().map { it.copy(available = readable(it.uri, it.bytes),
            needsUpdate = it.revision != com.cleartune.core.player.AudioFileRevision.get(context, it.account, it.song.id)) }.toMutableList()
        File(context.filesDir, "accounts").listFiles()?.filter { it.name.matches(Regex("[a-f0-9]{64}")) }?.forEach { folder ->
            val music = File(folder, "offline_music")
            if (!music.isDirectory) return@forEach
            val db = DatabaseFactory.create(context, folder.name)
            try {
                val rows = runCatching { db.downloadDao().all().associateBy { it.requestId } }.getOrDefault(emptyMap())
                val files = music.listFiles().orEmpty()
                files.filter { file -> file.isFile && (file.extension != "revision" ||
                    files.none { it != file && it.nameWithoutExtension == file.nameWithoutExtension }) }.forEach { file ->
                    val row = rows[file.nameWithoutExtension]
                    val song = row?.let { db.mediaDao().song(it.songId)?.toModel() }
                        ?: Song(id = row?.songId ?: file.name, title = file.name, suffix = file.extension)
                    val complete = row?.state == "COMPLETED" && file.extension !in setOf("part", "revision") && file.length() > 0 &&
                        (row.totalBytes == null || row.totalBytes == file.length())
                    // Active partials remain owned by their worker, not by the migration UI.
                    if (row?.state !in setOf("QUEUED", "DOWNLOADING")) result += DeviceDownload(
                        row?.requestId ?: file.name, folder.name, song, Uri.fromFile(file).toString(),
                        file.length(), file.name, legacy = true, complete = complete)
                }
            } finally { db.close() }
        }
        result
    }

    suspend fun publish(id: String, account: String, song: Song, source: File): DeviceDownload =
        withContext(Dispatchers.IO) { operations.withLock { publishLocked(id, account, song, source) } }

    private fun hash(input: java.io.InputStream): ByteArray = input.use { stream ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        digest.digest()
    }
    private fun publishLocked(id: String, account: String, song: Song, source: File): DeviceDownload {
        entries().firstOrNull { it.id == id && readable(it.uri, it.bytes) }?.let {
            check(it.account == account && it.song.id == song.id && it.bytes == source.length() &&
                it.digest == hash(source.inputStream()).joinToString("") { byte -> "%02x".format(byte) }) {
                "已有导出文件与原文件不一致，原文件已保留"
            }
            return it
        }
        var name = downloadFileName(song.artistName, song.title, song.suffix)
        val resolver = context.contentResolver
        // A journal allows a retry to clean an interrupted publication before writing again.
        index.getString("pending:$id", null)?.let { old ->
            deleteUri(old)
            check(index.edit().remove("pending:$id").commit())
        }
        val uri: Uri
        if (Build.VERSION.SDK_INT >= 29) {
            val existingNames = mutableSetOf<String>()
            resolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media.DISPLAY_NAME),
                "${MediaStore.Audio.Media.RELATIVE_PATH} = ?", arrayOf("Music/ClearTune/"), null)?.use { cursor ->
                while (cursor.moveToNext()) existingNames.add(cursor.getString(0).lowercase(java.util.Locale.ROOT))
            }
            name = availableDownloadName(name) { it.lowercase(java.util.Locale.ROOT) in existingNames }
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.TITLE, song.title)
                put(MediaStore.Audio.Media.ARTIST, song.artistName)
                put(MediaStore.Audio.Media.ALBUM, song.albumName)
                put(MediaStore.Audio.Media.MIME_TYPE, song.contentType ?: android.webkit.MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(song.suffix.orEmpty().lowercase()) ?: "audio/octet-stream")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/ClearTune/")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            uri = checkNotNull(resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)) { "无法创建公共音乐文件" }
        } else {
            check(context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                "请先在下载管理中允许存储权限，再重试下载"
            }
            val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "ClearTune")
            check(folder.isDirectory || folder.mkdirs()) { "无法创建音乐目录" }
            val baseName = name
            var file: File
            do {
                name = availableDownloadName(baseName) { File(folder, it).exists() }
                file = File(folder, name)
            } while (!file.createNewFile())
            uri = Uri.fromFile(file)
        }
        check(index.edit().putString("pending:$id", uri.toString()).commit()) { "无法记录下载进度" }
        try {
            // MediaStore also resolves collisions with files hidden from this app, including concurrent inserts.
            if (Build.VERSION.SDK_INT >= 29) {
                resolver.query(uri, arrayOf(MediaStore.Audio.Media.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) name = cursor.getString(0)
                }
            }
            check(android.os.StatFs(context.filesDir.path).availableBytes > source.length() + 10_485_760L) { "存储空间不足" }
            checkNotNull(resolver.openOutputStream(uri, "w")).use { output -> source.inputStream().use { it.copyTo(output) } }
            val digest = hash(checkNotNull(resolver.openInputStream(uri)))
            check(hash(source.inputStream()).contentEquals(digest)) { "文件校验失败，原文件已保留" }
            if (Build.VERSION.SDK_INT >= 29) check(resolver.update(uri, ContentValues().apply {
                put(MediaStore.Audio.Media.IS_PENDING, 0)
            }, null, null) == 1) { "无法发布下载文件" }
            else android.media.MediaScannerConnection.scanFile(context, arrayOf(uri.path!!), null, null)
            return DeviceDownload(id, account, song, uri.toString(), source.length(), name,
                digest = digest.joinToString("") { "%02x".format(it) },
                revision = com.cleartune.core.player.AudioFileRevision.get(context, account, song.id)).also(::save)
        } catch (error: Exception) {
            // Keep the journal if cleanup fails, so a retry can recover it.
            runCatching { deleteUri(uri.toString()); check(index.edit().remove("pending:$id").commit()) }
            throw error
        }
    }

    private fun deleteUri(value: String) {
        val uri = Uri.parse(value)
        if (uri.scheme == "file") {
            val file = File(requireNotNull(uri.path))
            check(!file.exists() || file.delete()) { "文件暂时无法删除" }
        } else {
            val count = context.contentResolver.delete(uri, null, null)
            check(count > 0 || !readable(value)) { "文件暂时无法删除，记录已保留" }
        }
    }

    suspend fun remove(item: DeviceDownload, deleteFile: Boolean) = withContext(Dispatchers.IO) { operations.withLock {
        check(com.cleartune.core.player.PlaybackCacheStore.currentLocalUri != item.uri) { "文件正在播放，请切换歌曲后再操作" }
        if (item.legacy) {
            val db = DatabaseFactory.create(context, item.account)
            try { check(db.downloadDao().forRequest(item.id)?.state !in setOf("QUEUED", "DOWNLOADING")) { "任务正在运行，请先暂停" } }
            finally { db.close() }
        }
        if (deleteFile) {
            deleteUri(item.uri)
            if (item.legacy) {
                val original = File(requireNotNull(Uri.parse(item.uri).path))
                File(original.parentFile, "${item.id}.revision").delete()
            }
        }
        if (item.legacy) {
            val db = DatabaseFactory.create(context, item.account)
            try {
                db.downloadDao().delete(item.id)
                com.cleartune.app.artwork.ArtworkCache.pruneOffline(context, item.account) {
                    db.downloadDao().all().mapNotNull { db.mediaDao().song(it.songId)?.coverArtId }.toSet()
                }
            } finally { db.close() }
        } else {
            check(index.edit().remove(item.id).commit()) { "无法更新下载记录" }
            val db = DatabaseFactory.create(context, item.account)
            try {
                db.downloadDao().delete(item.id)
                com.cleartune.app.artwork.ArtworkCache.pruneOffline(context, item.account) {
                    db.downloadDao().all().mapNotNull { db.mediaDao().song(it.songId)?.coverArtId }.toSet()
                }
            } finally { db.close() }
        }
    } }

    suspend fun migrate(item: DeviceDownload) = withContext(Dispatchers.IO) { operations.withLock {
        require(item.legacy && item.complete)
        check(com.cleartune.core.player.PlaybackCacheStore.currentLocalUri != item.uri) { "旧文件正在播放，请切换歌曲后再导出" }
        val source = File(requireNotNull(Uri.parse(item.uri).path))
        val db = DatabaseFactory.create(context, item.account)
        try {
            val row = db.downloadDao().forRequest(item.id)
            check(row?.state !in setOf("QUEUED", "DOWNLOADING")) { "下载任务仍在运行" }
            val published = publishLocked(item.id, item.account, item.song, source)
            if (row != null) db.downloadDao().upsert(row.copy(localUri = published.uri))
            check(source.delete()) { "已导出，旧文件暂时无法删除，请重试清理" }
            File(source.parentFile, "${item.id}.revision").delete()
        } finally { db.close() }
    } }

    suspend fun relocate(item: DeviceDownload, uri: Uri) = withContext(Dispatchers.IO) { operations.withLock {
        require(!item.legacy && readable(uri.toString(), item.bytes)) { "所选文件大小不匹配或无法读取" }
        require(item.digest.isNotEmpty()) { "原文件缺少校验信息，请重新下载" }
        val digest = hash(checkNotNull(context.contentResolver.openInputStream(uri))).joinToString("") { "%02x".format(it) }
        require(digest == item.digest) { "所选文件内容与原下载不一致" }
        save(item.copy(uri = uri.toString()))
    } }
}
