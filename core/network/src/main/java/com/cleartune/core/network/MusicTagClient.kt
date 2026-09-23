package com.cleartune.core.network

import com.cleartune.core.model.*
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.ConnectionPool
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

class MusicTagException(message: String, val httpStatus: Int? = null, val serviceCode: String? = null,
    val endpoint: String? = null) : IOException(message)

/** Downloaded cover and final payload are frozen before a write is journalled or sent. */
class PreparedMusicTagWrite internal constructor(internal val row: JsonObject, private val cover: ByteArray?) {
    val coverBytes: ByteArray? get() = cover?.copyOf()
    val coverHash: String? = cover?.let(::musicTagImageHash)
}

/** This adapter uses the token + /apimt contract. Never retries a write automatically. */
class MusicTagClient(settings: MusicTagSettings) {
    private val base = normalizeMusicTagAddress(settings.baseUrl, settings.allowHttp)
    private val username = settings.username
    private val password = settings.password
    private var token: String? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS)
        .callTimeout(55, TimeUnit.SECONDS).retryOnConnectionFailure(true)
        .followRedirects(false).followSslRedirects(false).build()
    // Reads can recover when a server closes an idle keep-alive connection. File writes cannot:
    // use a separate pool with no idle connections, and never replay an uncertain save.
    private val writeClient = client.newBuilder().retryOnConnectionFailure(false)
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS)).build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun login() {
        val reply = request("apimt/token/", buildJsonObject {
            put("username", username); put("password", password); put("code", "")
        }, authenticated = false)
        token = reply.text("token").takeIf(String::isNotBlank)
            ?: throw MusicTagException("Music Tag 登录未返回令牌")
    }

    suspend fun search(title: String, artist: String, album: String, source: String): List<MusicTagCandidate> {
        require(title.isNotBlank()) { "请输入歌曲标题" }
        val data = request("apimt/fetch_id3_by_title/", buildJsonObject {
            put("title", title); put("artist", artist); put("album", album)
            put("resource", source); put("full_path", "")
        })["data"] as? JsonArray ?: throw MusicTagException("候选列表格式不兼容")
        return data.mapNotNull { item ->
            val row = item as? JsonObject ?: return@mapNotNull null
            val id = row.text("id").ifBlank { row.text("musicid") }
            if (id.isBlank()) return@mapNotNull null
            MusicTagCandidate(id, row.text("resource").ifBlank { source }, buildMap {
                MusicTagField.entries.filter { it != MusicTagField.LYRICS }.forEach { field ->
                    val value = row.text(field.wireName).ifBlank {
                        when (field) {
                            MusicTagField.TITLE -> row.text("name")
                            MusicTagField.ARTIST -> row.text("singer")
                            else -> ""
                        }
                    }
                    if (value.isNotBlank() && value != "0") put(field, value)
                }
            })
        }
    }

    suspend fun lyrics(candidate: MusicTagCandidate): String {
        val value = request("apimt/fetch_lyric/", buildJsonObject {
            put("resource", candidate.source); put("song_id", candidate.id)
        })["data"]?.let { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        if (value.isBlank() || value.startsWith("未找到歌词")) throw MusicTagException("该来源未找到歌词")
        return value
    }

    suspend fun read(path: String): MusicTagSnapshot {
        val row = request("apimt/music_id3/", buildJsonObject {
            put("file_path", path.substringBeforeLast('/')); put("file_name", path.substringAfterLast('/'))
        })["data"] as? JsonObject ?: throw MusicTagException("无法读取目标文件标签")
        if (row.text("filename") != path.substringAfterLast('/')) throw MusicTagException("服务返回的文件名不匹配")
        val returnedPath = row.text("path")
        if (returnedPath.isNotBlank() && returnedPath != path) throw MusicTagException("服务返回的文件路径不匹配")
        return MusicTagSnapshot(path, MusicTagField.entries.associateWith {
            row.text(if (it == MusicTagField.COVER) "artwork" else it.wireName)
        })
    }

    suspend fun prepareWrite(snapshot: MusicTagSnapshot, changes: Map<MusicTagField, String>): PreparedMusicTagWrite {
        val row = musicTagWritePayload(snapshot.path, changes).toMutableMap()
        val cover = changes[MusicTagField.COVER]?.let {
            try { downloadCover(it) }
            catch (error: MusicTagException) { throw error }
            catch (error: IOException) { throw MusicTagException("封面下载中断或超时，尚未提交保存，请重试") }
        }
        if (cover != null) {
            // The web upload endpoint returns raw Base64, which the editor assigns to album_img.
            // Do not send a URL in artwork: the save handler must never download itself.
            row["album_img"] = JsonPrimitive(java.util.Base64.getEncoder().encodeToString(cover))
            row.remove("artwork")
        }
        return PreparedMusicTagWrite(JsonObject(row), cover)
    }

    suspend fun write(snapshot: MusicTagSnapshot, changes: Map<MusicTagField, String>) = write(prepareWrite(snapshot, changes))

    suspend fun write(prepared: PreparedMusicTagWrite) {
        request("apimt/update_id3/", buildJsonObject { put("music_id3_info", JsonArray(listOf(prepared.row))) })
    }

    private suspend fun downloadCover(url: String): ByteArray = withContext(Dispatchers.IO) {
        var current = url
        repeat(4) { hop ->
            ensureActive()
            // No auth headers or cookies, including for the Music Tag host and redirects.
            client.newCall(Request.Builder().url(current).get().build()).execute().use { response ->
                ensureActive()
                if (response.code in setOf(301, 302, 303, 307, 308)) {
                    val next = response.header("Location")?.let { response.request.url.resolve(it) }
                    if (hop == 3 || next == null || next.username.isNotEmpty() || next.password.isNotEmpty() ||
                        (response.request.url.isHttps && !next.isHttps))
                        throw MusicTagException("封面地址跳转异常，尚未提交保存，请更换封面")
                    current = next.toString()
                } else {
                    if (!response.isSuccessful) throw MusicTagException("封面下载失败（HTTP ${response.code}），尚未提交保存")
                    val body = response.body ?: throw MusicTagException("封面内容为空，尚未提交保存")
                    if (body.contentLength() > MAX_COVER_BYTES) throw MusicTagException("封面不能超过 5 MB，尚未提交保存")
                    val bytes = readLimited(body.byteStream(), MAX_COVER_BYTES, "封面不能超过 5 MB，尚未提交保存")
                    ensureActive()
                    if (!isMusicTagImage(bytes)) throw MusicTagException("下载内容不是支持的图片，尚未提交保存，请更换封面")
                    return@withContext bytes
                }
            }
        }
        error("Unreachable redirect state")
    }

    suspend fun artworkMatches(path: String, url: String): Boolean? = artworkMatchesExpected(path, null, url)

    suspend fun artworkMatchesHash(path: String, expectedHash: String): Boolean? = artworkMatchesExpected(path, expectedHash, null)

    private suspend fun artworkMatchesExpected(path: String, expectedHash: String?, url: String?): Boolean? {
        val reply = try {
            request("apimt/get_original_artwork/", buildJsonObject { put("file_full_path", path) })
        } catch (error: MusicTagException) {
            // V1 explicitly denies this optional capability even with a freshly issued valid token.
            if (error.httpStatus == 403 && error.serviceCode == "40302") return null
            throw error
        }
        val artwork = reply["data"]
            ?.let { (it as? JsonObject)?.text("artwork") }.orEmpty()
        if (!artwork.startsWith("data:image/") || ";base64," !in artwork) return false
        val actual = runCatching { java.util.Base64.getMimeDecoder().decode(artwork.substringAfter(',')) }.getOrNull() ?: return false
        if (expectedHash != null) return musicTagImageHash(actual) == expectedHash
        // Separate unauthenticated request: never send either server's credentials to an image source.
        val expected = withContext(Dispatchers.IO) {
            client.newCall(Request.Builder().url(requireNotNull(url)).build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body?.byteStream()?.let(::readLimited)
            }
        } ?: return false
        return actual.contentEquals(expected)
    }

    private suspend fun request(path: String, body: JsonObject, authenticated: Boolean = true): JsonObject =
        withContext(Dispatchers.IO) {
            ensureActive()
            val builder = Request.Builder().url(base + path)
                .header("X-Requested-With", "XMLHttpRequest")
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            if (authenticated) builder.header("AUTHORIZATION", "jwt ${token ?: throw MusicTagException("请先连接 Music Tag")}")
            val transport = if (path in RECOVERABLE_ENDPOINTS) client else writeClient
            try { transport.newCall(builder.build()).execute().use { response ->
                ensureActive()
                val stream = response.body?.byteStream() ?: throw MusicTagException("Music Tag 返回空响应")
                val bytes = readLimited(stream)
                val reply = runCatching { json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()
                if (!response.isSuccessful) throw MusicTagException(musicTagHttpFailure(response.code, reply?.text("code"), authenticated),
                    response.code, reply?.text("code"), path)
                if (reply == null) throw MusicTagException("Music Tag 返回格式不兼容，请检查服务地址")
                if ((reply["result"] as? JsonPrimitive)?.booleanOrNull != true) {
                    // Do not expose arbitrary server messages containing paths, tokens or credentials.
                    throw MusicTagException(musicTagFailureMessage(path, reply.text("message")))
                }
                reply
            } } catch (error: MusicTagException) { throw error }
            catch (error: IOException) {
                ensureActive()
                val message = when (path) {
                    "apimt/token/" -> "连接 Music Tag 登录接口失败，请检查连接后重试"
                    "apimt/music_id3/" -> "读取原标签时连接中断，请重试；本次读取未修改文件"
                    "apimt/update_id3/" -> "保存请求连接中断，结果尚未确认，请检查保存结果"
                    "apimt/get_original_artwork/" -> "读取文件封面时连接中断，请重新检查保存结果"
                    else -> "获取刮削信息时连接中断，请重试或切换来源"
                }
                throw MusicTagException(message, endpoint = path).apply { initCause(error) }
            }
        }

    companion object {
        private const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
        private const val MAX_COVER_BYTES = 5 * 1024 * 1024
        private val RECOVERABLE_ENDPOINTS = setOf("apimt/token/", "apimt/music_id3/",
            "apimt/fetch_id3_by_title/", "apimt/fetch_lyric/", "apimt/get_original_artwork/")
    }

    private fun readLimited(stream: java.io.InputStream, limit: Int = MAX_RESPONSE_BYTES, message: String = "Music Tag 响应过大"): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            if (output.size() + count > limit) throw MusicTagException(message)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}

fun musicTagImageHash(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

private fun isMusicTagImage(bytes: ByteArray): Boolean {
    fun starts(vararg values: Int) = bytes.size >= values.size && values.indices.all { (bytes[it].toInt() and 255) == values[it] }
    return starts(0x89, 0x50, 0x4e, 0x47, 13, 10, 26, 10) || starts(0xff, 0xd8, 0xff) ||
        starts(0x47, 0x49, 0x46, 0x38) || starts(0x42, 0x4d) ||
        (bytes.size >= 12 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
            bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP")
}

internal fun JsonObject.text(key: String): String = when (val value = this[key]) {
    is JsonPrimitive -> value.contentOrNull.orEmpty()
    is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString("; ")
    else -> ""
}

internal fun musicTagFailureMessage(endpoint: String, serverMessage: String): String {
    // Classify known failures without displaying arbitrary server data or credentials.
    if (serverMessage.contains("No such file or directory", ignoreCase = true) ||
        serverMessage.contains("文件不存在") || serverMessage.contains("文件夹不存在"))
        return "Music Tag 找不到目标文件，请核对下方文件路径和服务目录映射"
    if (serverMessage.contains("Permission denied", ignoreCase = true) || serverMessage.contains("无权限"))
        return "Music Tag 没有访问目标文件的权限，请检查服务的音乐目录权限"
    return when (endpoint) {
        "apimt/music_id3/" -> "原标签读取失败，请核对文件路径、读取权限及音频格式"
        "apimt/token/" -> "Music Tag 登录失败，请检查账号、密码和服务地址"
        "apimt/fetch_id3_by_title/", "apimt/fetch_lyric/" -> "刮削来源暂不可用，请稍后重试或切换来源"
        else -> "Music Tag 未完成请求，请检查服务状态后重试"
    }
}

internal fun musicTagHttpFailure(status: Int, code: String?, authenticated: Boolean): String = when {
    status == 400 && code == "40000" -> "Music Tag 拒绝了提交参数，文件未写入（接口字段校验失败）"
    status == 403 && code == "40302" -> "当前 Music Tag 版本不支持此接口，登录仍然有效"
    status == 401 && authenticated -> "Music Tag 登录凭据已失效，请重新连接服务"
    status == 401 -> "Music Tag 登录失败，请检查账号和密码"
    status == 403 -> "Music Tag 拒绝访问此接口，请检查账号权限"
    status in 300..399 -> "服务发生跳转，请填写最终服务地址"
    else -> "Music Tag 请求失败（$status）"
}

fun normalizeMusicTagAddress(address: String, allowHttp: Boolean): String {
    val normalized = ServerAddressNormalizer.normalize(address, allowHttp).getOrThrow()
    // Accept pasted web entry URLs while retaining a reverse proxy's path prefix.
    val entry = URI(normalized).path.trimEnd('/').substringAfterLast('/')
    return if (entry in setOf("login", "admin")) normalized.removeSuffix("$entry/") else normalized
}

internal fun musicTagWritePayload(path: String, changes: Map<MusicTagField, String>): JsonObject {
    require(changes.isNotEmpty()) { "请先选择要应用的标签" }
    require(path.startsWith('/') && path.split('/').none { it == ".." || it == "." } && "\${" !in path) { "目标文件路径无效" }
    return buildJsonObject {
        put("file_full_path", path)
        put("filename", path.substringAfterLast('/'))
        put("is_save_lyrics_file", false)
        put("is_save_album_cover", false)
        // V1 requires these keys. JSON null means leave the file's tag unchanged;
        // an empty string or ${null} template can instead remove a tag.
        listOf("title", "artist", "album", "albumartist", "discnumber", "tracknumber",
            "genre", "year", "lyrics", "comment").forEach { put(it, JsonNull) }
        changes.forEach { (field, value) ->
            require(value.isNotBlank()) { "本版不支持清空标签，请取消勾选空白字段" }
            require("\${" !in value) { "标签不能包含 Music Tag 模板表达式" }
            if (field == MusicTagField.YEAR) require(value.toIntOrNull() in 1..9999) { "年份应为 1–9999" }
            if (field == MusicTagField.COVER) {
                val uri = runCatching { URI(value) }.getOrNull()
                require(uri?.scheme in setOf("https", "http") && !uri?.host.isNullOrBlank() && uri?.userInfo == null) { "封面地址无效" }
            }
            put(field.wireName, value)
            // V1's web editor sends both fields for an explicitly selected replacement cover.
            if (field == MusicTagField.COVER) put("artwork", value)
        }
    }
}

fun validateMusicTagChanges(path: String, changes: Map<MusicTagField, String>) { musicTagWritePayload(path, changes) }
