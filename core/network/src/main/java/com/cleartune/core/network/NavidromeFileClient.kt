package com.cleartune.core.network

import com.cleartune.core.model.ServerCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Navidrome's Subsonic path is virtual unless ReportRealPath is enabled for the player.
 * The native song endpoint resolves the same song ID to its actual library-relative path.
 * Only authentication and read requests are issued; no player/server configuration is changed.
 */
class NavidromeFileClient(private val credentials: ServerCredentials) {
    private val base = ServerAddressNormalizer.normalize(credentials.baseUrl, credentials.allowInsecureHttp).getOrThrow().toHttpUrl()
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

    suspend fun songPath(id: String, sourceRoot: String): String = withContext(Dispatchers.IO) {
        require(id.isNotBlank() && id != "." && id != "..") { "歌曲 ID 无效" }
        ensureActive()
        val loginUrl = base.newBuilder().addPathSegments("auth/login").build()
        val login = request(Request.Builder().url(loginUrl).post(buildJsonObject {
            put("username", credentials.username); put("password", credentials.password)
        }.toString().toRequestBody("application/json".toMediaType())).build())
        val token = login.text("token").takeIf(String::isNotBlank)
            ?: throw MusicTagException("Navidrome 未返回登录令牌，无法定位原音乐文件")
        ensureActive()
        val songUrl = base.newBuilder().addPathSegments("api/song").addPathSegment(id).build()
        val song = request(Request.Builder().url(songUrl).header("x-nd-authorization", "Bearer $token").get().build())
        ensureActive()
        navidromeMappingPath(song, id, sourceRoot)
    }

    private fun request(request: Request): JsonObject = client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw MusicTagException(when (response.code) {
            401, 403 -> "无法获取真实文件路径，请检查 Navidrome 账号权限或为 ClearTune 启用报告真实路径"
            404 -> "Navidrome 未提供该歌曲的真实路径，请检查歌曲是否存在或为 ClearTune 启用报告真实路径"
            in 300..399 -> "Navidrome 发生跳转，请检查音乐服务器地址"
            else -> "Navidrome 文件定位失败（${response.code}），请稍后重试"
        })
        val stream = response.body.byteStream()
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            if (out.size() + count > 8 * 1024 * 1024) throw MusicTagException("Navidrome 歌曲响应过大")
            out.write(buffer, 0, count)
        }
        runCatching { Json.parseToJsonElement(out.toString(Charsets.UTF_8.name())) as? JsonObject }.getOrNull()
            ?: throw MusicTagException("Navidrome 歌曲响应不兼容，无法获取真实文件路径")
    }
}

internal fun navidromeMappingPath(song: JsonObject, id: String, sourceRoot: String): String {
    check(song.text("id") == id) { "Navidrome 返回的歌曲 ID 不匹配" }
    check((song["missing"] as? JsonPrimitive)?.booleanOrNull != true) { "Navidrome 标记此音乐文件已丢失，请先检查音乐库" }
    val path = song.text("path").takeIf(String::isNotBlank) ?: throw MusicTagException("Navidrome 未返回真实文件路径")
    if (path.startsWith('/') || sourceRoot.isBlank()) return path
    val library = song.text("libraryPath").trimEnd('/')
    check(library.startsWith('/')) { "Navidrome 未返回音乐库根目录，无法使用当前目录映射" }
    return "$library/$path"
}
