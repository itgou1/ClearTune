package com.cleartune.core.network

import com.cleartune.core.model.MusicTagPath
import com.cleartune.core.model.ServerCredentials
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NavidromeFileClientTest {
    private fun song(id: String = "song-id", path: String = "我的音乐集/morning - 卫兰.mp3", missing: Boolean = false) = buildJsonObject {
        put("id", id); put("path", path); put("libraryPath", "/opt/navidrome/music"); put("missing", missing)
    }

    @Test fun realRelativePathPreservesFilenameAndSupportsExplicitMapping() {
        val relative = navidromeMappingPath(song(), "song-id", "")
        assertEquals("/app/media/我的音乐集/morning - 卫兰.mp3", MusicTagPath.resolve(relative, "", "/app/media"))
        val absolute = navidromeMappingPath(song(), "song-id", "/opt/navidrome/music")
        assertEquals("/app/media/我的音乐集/morning - 卫兰.mp3", MusicTagPath.resolve(absolute, "/opt/navidrome/music", "/app/media"))
    }

    @Test fun rejectsWrongSongMissingFileAndUnsafePath() {
        assertThrows(IllegalStateException::class.java) { navidromeMappingPath(song("other"), "song-id", "") }
        assertThrows(IllegalStateException::class.java) { navidromeMappingPath(song(missing = true), "song-id", "") }
        assertThrows(IllegalArgumentException::class.java) {
            MusicTagPath.resolve(navidromeMappingPath(song(path = "../escape.mp3"), "song-id", ""), "", "/app/media")
        }
    }

    @Test fun authenticatesToNavidromeAndReadsExactIdUnderProxyPrefix() = runBlocking<Unit> {
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/nav/") { exchange ->
            calls.add("${exchange.requestMethod} ${exchange.requestURI.path}")
            val response = when (exchange.requestURI.path) {
                "/nav/auth/login" -> {
                    assertNull(exchange.requestHeaders.getFirst("x-nd-authorization"))
                    val body = Json.parseToJsonElement(exchange.requestBody.readBytes().toString(Charsets.UTF_8)).jsonObject
                    assertEquals("nav-secret", body.text("password"))
                    """{"token":"native-token"}"""
                }
                "/nav/api/song/song-id" -> {
                    assertEquals("Bearer native-token", exchange.requestHeaders.getFirst("x-nd-authorization"))
                    song().toString()
                }
                else -> error("Unexpected endpoint")
            }.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val client = NavidromeFileClient(ServerCredentials("http://127.0.0.1:${server.address.port}/nav/", "nav-user", "nav-secret", allowInsecureHttp = true))
            assertEquals("我的音乐集/morning - 卫兰.mp3", client.songPath("song-id", ""))
            assertEquals(listOf("POST /nav/auth/login", "GET /nav/api/song/song-id"), calls.toList())
        } finally { server.stop(0) }
    }

    @Test fun doesNotFollowAuthenticationRedirects() = runBlocking<Unit> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var followed = false
        server.createContext("/auth/login") { exchange ->
            exchange.responseHeaders.add("Location", "/unexpected")
            exchange.sendResponseHeaders(307, -1); exchange.close()
        }
        server.createContext("/unexpected") { exchange -> followed = true; exchange.sendResponseHeaders(500, -1); exchange.close() }
        server.start()
        try {
            val client = NavidromeFileClient(ServerCredentials("http://127.0.0.1:${server.address.port}/", "test", "secret", allowInsecureHttp = true))
            assertTrue(runCatching { client.songPath("song-id", "") }.exceptionOrNull() is MusicTagException)
            assertFalse(followed)
        } finally { server.stop(0) }
    }
}
