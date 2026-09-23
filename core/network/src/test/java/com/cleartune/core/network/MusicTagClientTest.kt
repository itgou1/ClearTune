package com.cleartune.core.network

import com.cleartune.core.model.*
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MusicTagClientTest {
    @Test fun coverReplacementSendsBothV1FieldsWithoutOtherTags() {
        val url = "https://example.com/new.jpg"
        val payload = musicTagWritePayload("/app/media/a.mp3", mapOf(MusicTagField.COVER to url))
        assertEquals(url, payload.text("album_img"))
        assertEquals(url, payload.text("artwork"))
        assertEquals(JsonNull, payload["title"])
        assertEquals(JsonNull, payload["lyrics"])
    }

    @Test fun v1ArtworkPermissionIsNotExpiredAuthentication() = runBlocking<Unit> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var writes = 0
        server.createContext("/apimt/") { exchange ->
            val response = when (exchange.requestURI.path) {
                "/apimt/token/" -> 200 to """{"result":true,"token":"valid-token"}"""
                "/apimt/get_original_artwork/" -> {
                    assertEquals("jwt valid-token", exchange.requestHeaders.getFirst("AUTHORIZATION"))
                    403 to """{"result":false,"code":"40302","message":"V1 version restriction"}"""
                }
                else -> { writes++; 500 to "{}" }
            }
            val bytes = response.second.toByteArray()
            exchange.sendResponseHeaders(response.first, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = MusicTagClient(MusicTagSettings("http://127.0.0.1:${server.address.port}", "u", "p", allowHttp = true))
            client.login()
            assertNull(client.artworkMatches("/app/media/a.mp3", "https://unused.invalid/cover.jpg"))
            assertEquals(0, writes)
            assertFalse(musicTagHttpFailure(403, "40302", true).contains("过期"))
            assertTrue(musicTagHttpFailure(403, "other", true).contains("权限"))
            assertTrue(musicTagHttpFailure(401, null, true).contains("失效"))
        } finally { server.stop(0) }
    }

    @Test fun rejectedWriteIsNotRetriedAndKeepsOperationDetails() = runBlocking<Unit> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var writes = 0
        server.createContext("/apimt/") { exchange ->
            val login = exchange.requestURI.path.endsWith("token/")
            if (!login) writes++
            val bytes = (if (login) """{"result":true,"token":"valid-token"}""" else """{"code":"40302","result":false}""").toByteArray()
            exchange.sendResponseHeaders(if (login) 200 else 403, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = MusicTagClient(MusicTagSettings("http://127.0.0.1:${server.address.port}", "u", "p", allowHttp = true))
            client.login()
            val error = runCatching { client.write(MusicTagSnapshot("/app/media/a.mp3", emptyMap()), mapOf(MusicTagField.TITLE to "new")) }.exceptionOrNull() as MusicTagException
            assertEquals(403, error.httpStatus)
            assertEquals("apimt/update_id3/", error.endpoint)
            assertEquals(1, writes)
        } finally { server.stop(0) }
    }
    @Test fun fileErrorsAreActionableWithoutExposingServerDetails() {
        val missing = musicTagFailureMessage("apimt/music_id3/", "[Errno 2] No such file or directory: '/private/song.mp3'")
        assertTrue(missing.contains("找不到目标文件"))
        assertFalse(missing.contains("/private"))
        assertTrue(musicTagFailureMessage("apimt/music_id3/", "Permission denied: private").contains("权限"))
        assertTrue(musicTagFailureMessage("apimt/music_id3/", "unknown-secret").contains("原标签读取失败"))
        assertFalse(musicTagFailureMessage("apimt/music_id3/", "unknown-secret").contains("unknown-secret"))
    }
    @Test fun normalizesLoginUrlAndKeepsProxyPrefix() {
        assertEquals("https://example.com/tag/", normalizeMusicTagAddress("https://example.com/tag/login", false))
        assertThrows(AddressException::class.java) { normalizeMusicTagAddress("http://example.com/login", false) }
        assertThrows(AddressException::class.java) { normalizeMusicTagAddress("https://user:pass@example.com", false) }
    }

    @Test fun partialWriteContainsExactlyOneFileAndOnlyChosenTags() {
        val row = musicTagWritePayload("/app/media/a.flac", mapOf(MusicTagField.TITLE to "新标题"))
        assertEquals("新标题", row.text("title"))
        listOf("artist", "album", "albumartist", "discnumber", "tracknumber", "genre", "year", "lyrics", "comment")
            .forEach { assertEquals("$it must be a no-op null, not an empty string", JsonNull, row[it]) }
        assertEquals("a.flac", row.text("filename"))
        assertFalse(row.containsKey("artwork"))
        assertFalse(row.containsKey("album_img"))
        assertEquals(JsonNull, row["lyrics"])
    }

    @Test fun normalizesAdminEntryAndKeepsOtherProxyPaths() {
        assertEquals("http://example.com:8002/", normalizeMusicTagAddress("http://example.com:8002/admin", true))
        assertEquals("https://example.com/tag/", normalizeMusicTagAddress("https://example.com/tag/admin/", false))
        assertEquals("https://example.com/tag/", normalizeMusicTagAddress("https://example.com/tag/", false))
        assertEquals("https://example.com/my-admin/", normalizeMusicTagAddress("https://example.com/my-admin", false))
    }

    @Test fun rejectsClearAndTemplatesBeforeSending() {
        listOf("", "\${null}", "prefix \${artist}").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                musicTagWritePayload("/app/media/a.flac", mapOf(MusicTagField.TITLE to value))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            musicTagWritePayload("/app/media/a.flac", mapOf(MusicTagField.COVER to "file:///private/image"))
        }
    }

    @Test fun tokenSearchReadAndSingleWriteUseActualServiceContract() = runBlocking {
        val writes = CopyOnWriteArrayList<JsonObject>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/tag/") { exchange ->
            val body = Json.parseToJsonElement(exchange.requestBody.readBytes().toString(Charsets.UTF_8)).jsonObject
            val path = exchange.requestURI.path.removePrefix("/tag/")
            val response = when (path) {
                "apimt/token/" -> {
                    assertNull(exchange.requestHeaders.getFirst("AUTHORIZATION"))
                    assertEquals("secret", body.text("password"))
                    """{"result":true,"token":"sample-token"}"""
                }
                "apimt/fetch_id3_by_title/" -> {
                    assertEquals("jwt sample-token", exchange.requestHeaders.getFirst("AUTHORIZATION"))
                    assertEquals("", body.text("full_path"))
                    """{"result":true,"data":[{"id":123,"name":"候选","artist":"歌手","album":"专辑","year":2024,"album_img":"https://example.com/cover.jpg"}]}"""
                }
                "apimt/music_id3/" -> {
                    assertEquals("/app/media", body.text("file_path"))
                    """{"result":true,"data":{"filename":"a.flac","path":"/app/media/a.flac","title":"原曲","artwork":"preview-only"}}"""
                }
                "apimt/update_id3/" -> {
                    val row = body.getValue("music_id3_info").jsonArray.single().jsonObject
                    assertTrue(listOf("title", "artist", "album").all { row.containsKey(it) })
                    assertEquals(JsonNull, row["artist"])
                    assertEquals(JsonNull, row["album"])
                    writes.add(body)
                    """{"result":true,"data":null}"""
                }
                else -> error("Unexpected call $path")
            }.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val client = MusicTagClient(MusicTagSettings("http://127.0.0.1:${server.address.port}/tag/admin", "admin", "secret", allowHttp = true))
            client.login()
            val candidates = client.search("query", "artist", "album", "netease")
            assertEquals("123", candidates.single().id)
            assertEquals("候选", candidates.single().values[MusicTagField.TITLE])
            assertEquals("2024", candidates.single().values[MusicTagField.YEAR])
            val snapshot = client.read("/app/media/a.flac")
            client.write(snapshot, mapOf(MusicTagField.TITLE to "候选"))
            val rows = writes.single().getValue("music_id3_info").jsonArray
            assertEquals(1, rows.size)
            assertFalse(rows.single().jsonObject.containsKey("artwork"))
        } finally { server.stop(0) }
    }

    @Test fun http200BusinessFailureIsNotSuccess() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/apimt/token/") { exchange ->
            val body = """{"result":false,"message":"private-server-path"}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val client = MusicTagClient(MusicTagSettings("http://127.0.0.1:${server.address.port}", "u", "p", allowHttp = true))
            val failure = runCatching { client.login() }.exceptionOrNull()
            assertTrue(failure is MusicTagException)
            assertFalse(failure?.message.orEmpty().contains("private-server-path"))
        } finally { server.stop(0) }
    }

    @Test fun fieldValidationFailureKeepsCodeAndDoesNotRetry() = runBlocking<Unit> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var writes = 0
        server.createContext("/apimt/") { exchange ->
            val login = exchange.requestURI.path.endsWith("token/")
            if (!login) writes++
            val response = if (login) """{"result":true,"token":"token"}"""
                else """{"result":false,"code":"40000","message":"private field details"}"""
            val bytes = response.toByteArray()
            exchange.sendResponseHeaders(if (login) 200 else 400, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = MusicTagClient(MusicTagSettings("http://127.0.0.1:${server.address.port}", "u", "p", allowHttp = true))
            client.login()
            val failure = runCatching {
                client.write(MusicTagSnapshot("/app/media/a.mp3", emptyMap()), mapOf(MusicTagField.YEAR to "2010"))
            }.exceptionOrNull() as MusicTagException
            assertEquals(400, failure.httpStatus)
            assertEquals("40000", failure.serviceCode)
            assertEquals("apimt/update_id3/", failure.endpoint)
            assertTrue(failure.message.orEmpty().contains("文件未写入"))
            assertFalse(failure.message.orEmpty().contains("private"))
            assertEquals(1, writes)
        } finally { server.stop(0) }
    }
}
