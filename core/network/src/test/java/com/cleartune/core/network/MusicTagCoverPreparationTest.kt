package com.cleartune.core.network

import com.cleartune.core.model.*
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MusicTagCoverPreparationTest {
    private val image = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a1ioAAAAASUVORK5CYII=")
    private val snapshot = MusicTagSnapshot("/app/media/test.mp3", emptyMap())

    @Test fun selfHostedCoverIsFetchedBeforeWriteAndSentAsFrozenBase64() = runBlocking {
        val requests = CopyOnWriteArrayList<String>()
        val rows = CopyOnWriteArrayList<JsonObject>()
        val authOnImages = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            requests.add(path)
            val response = when (path) {
                "/apimt/token/" -> """{"result":true,"token":"private-token"}""".toByteArray()
                "/cover" -> {
                    exchange.requestHeaders.getFirst("Authorization")?.let(authOnImages::add)
                    exchange.requestHeaders.getFirst("Cookie")?.let(authOnImages::add)
                    image
                }
                "/apimt/update_id3/" -> {
                    rows.add(Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
                        .getValue("music_id3_info").jsonArray.single().jsonObject)
                    """{"result":true}""".toByteArray()
                }
                else -> error(path)
            }
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val client = MusicTagClient(MusicTagSettings(base, "u", "p", allowHttp = true))
            client.login()
            val prepared = client.prepareWrite(snapshot, mapOf(MusicTagField.COVER to "$base/cover", MusicTagField.ALBUM to "new"))
            assertEquals(listOf("/apimt/token/", "/cover"), requests.toList())
            prepared.coverBytes!![0] = 0 // Callers cannot mutate the frozen payload.
            client.write(prepared)
            val row = rows.single()
            assertArrayEquals(image, Base64.getDecoder().decode(row.getValue("album_img").jsonPrimitive.content))
            assertFalse(row.containsKey("artwork"))
            assertEquals("new", row.getValue("album").jsonPrimitive.content)
            assertEquals(JsonNull, row["title"])
            assertEquals(musicTagImageHash(image), prepared.coverHash)
            assertEquals(listOf("/apimt/token/", "/cover", "/apimt/update_id3/"), requests.toList())
            assertTrue(authOnImages.isEmpty())
        } finally { server.stop(0) }
    }

    @Test fun invalidOrOversizedDownloadsNeverReachSave() = runBlocking {
        for (case in listOf("http", "html", "length", "chunked")) {
            var writes = 0
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                if (exchange.requestURI.path != "/cover") writes++
                val bytes = when (case) {
                    "length", "chunked" -> ByteArray(5 * 1024 * 1024 + 1)
                    else -> "<html>login required</html>".toByteArray()
                }
                exchange.sendResponseHeaders(if (case == "http") 403 else 200, if (case == "chunked") 0 else bytes.size.toLong())
                runCatching { exchange.responseBody.use { it.write(bytes) } }
                exchange.close()
            }
            server.start()
            try {
                val base = "http://127.0.0.1:${server.address.port}"
                val client = MusicTagClient(MusicTagSettings(base, "u", "p", allowHttp = true))
                val error = runCatching { client.write(snapshot, mapOf(MusicTagField.COVER to "$base/cover")) }.exceptionOrNull()
                assertTrue("$case: $error", error is MusicTagException)
                assertTrue(error!!.message.orEmpty().contains("尚未提交保存"))
                assertEquals(0, writes)
            } finally { server.stop(0) }
        }
    }

    @Test fun redirectsDownloadImagesWithoutServiceCredentials() = runBlocking {
        val auth = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestHeaders.getFirst("Authorization")?.let(auth::add)
            if (exchange.requestURI.path == "/redirect") {
                exchange.responseHeaders.add("Location", "/image")
                exchange.sendResponseHeaders(302, -1)
            } else {
                exchange.sendResponseHeaders(200, image.size.toLong())
                exchange.responseBody.use { it.write(image) }
            }
            exchange.close()
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val prepared = MusicTagClient(MusicTagSettings(base, "u", "p", allowHttp = true))
                .prepareWrite(snapshot, mapOf(MusicTagField.COVER to "$base/redirect"))
            assertArrayEquals(image, prepared.coverBytes)
            assertTrue(auth.isEmpty())
        } finally { server.stop(0) }
    }

    @Test fun readBackComparesSubmittedBytesWithoutRefetchingTheSource() = runBlocking {
        val paths = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            paths.add(exchange.requestURI.path)
            val response = if (exchange.requestURI.path.endsWith("token/")) """{"result":true,"token":"t"}"""
                else """{"result":true,"data":{"artwork":"data:image/png;base64,${Base64.getEncoder().encodeToString(image)}"}}"""
            val bytes = response.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = MusicTagClient(MusicTagSettings("http://127.0.0.1:${server.address.port}", "u", "p", allowHttp = true))
            client.login()
            assertEquals(true, client.artworkMatchesHash(snapshot.path, musicTagImageHash(image)))
            assertEquals(false, client.artworkMatchesHash(snapshot.path, musicTagImageHash(byteArrayOf(1))))
            assertEquals(listOf("/apimt/token/", "/apimt/get_original_artwork/", "/apimt/get_original_artwork/"), paths.toList())
        } finally { server.stop(0) }
    }
}
