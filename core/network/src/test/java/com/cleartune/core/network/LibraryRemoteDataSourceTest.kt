package com.cleartune.core.network

import com.cleartune.core.model.ServerCredentials
import com.cleartune.core.model.Song
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRemoteDataSourceTest {
    @Test
    fun mapsSynchronizedLyrics() = runBlocking {
        val server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/rest/getLyricsBySongId.view") { exchange ->
                exchange.respond(
                    """{"subsonic-response":{"status":"ok","version":"1.16.1","lyricsList":{"structuredLyrics":[{"synced":true,"line":[{"start":1200,"value":"第一句"},{"start":3400,"value":"第二句"}]}]}}}""",
                )
            }
            start()
        }
        try {
            val remote = LibraryRemoteDataSource(
                OpenSubsonicApiFactory().authorized(
                    ServerCredentials(
                        baseUrl = "http://127.0.0.1:${server.address.port}",
                        username = "alice",
                        password = "secret",
                        allowInsecureHttp = true,
                    ),
                ),
            )
            val result = remote.lyrics(Song(id = "song-1", title = "月光")) as RemoteResult.Success
            assertTrue(result.value.synced)
            assertEquals(1200L, result.value.lines.first().startMs)
            assertEquals("第二句", result.value.lines.last().text)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun streamUrlUsesTokenAndTranscodeParametersWithoutPassword() {
        val authorized = OpenSubsonicApiFactory().authorized(
            ServerCredentials(
                baseUrl = "https://music.example.com",
                username = "alice",
                password = "secret",
            ),
        )

        val url = authorized.streamUrl("song 1", maxBitRate = 192, format = "mp3")

        assertTrue(url.startsWith("https://music.example.com/rest/stream.view?"))
        assertTrue("id=song+1" in url)
        assertTrue("maxBitRate=192" in url && "format=mp3" in url)
        assertTrue("p=" !in url && "secret" !in url)
        assertTrue("t=" in url && "s=" in url)
    }

    @Test
    fun mapsAlbumsAndUsesEmptySearchForSongPaging() = runBlocking {
        val requests = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/rest/getAlbumList2.view") { exchange ->
                requests += exchange.requestURI.toString()
                exchange.respond(
                    """{"subsonic-response":{"status":"ok","version":"1.16.1","albumList2":{"album":[{"id":"album-1","name":"夜航","artist":"林海","artistId":"artist-1","songCount":8,"duration":1800}]}}}""",
                )
            }
            createContext("/rest/search3.view") { exchange ->
                requests += exchange.requestURI.toString()
                exchange.respond(
                    """{"subsonic-response":{"status":"ok","version":"1.16.1","searchResult3":{"song":[{"id":"song-1","title":"月光","artist":"林海","artistId":"artist-1","album":"夜航","albumId":"album-1","duration":245,"replayGain":{"trackGain":-7.2,"albumGain":-5.1,"trackPeak":0.97,"albumPeak":0.99}}]}}}""",
                )
            }
            start()
        }
        try {
            val remote = LibraryRemoteDataSource(
                OpenSubsonicApiFactory().authorized(
                    ServerCredentials(
                        baseUrl = "http://127.0.0.1:${server.address.port}",
                        username = "alice",
                        password = "secret",
                        allowInsecureHttp = true,
                    ),
                ),
            )

            val albums = remote.albums(size = 25, offset = 50) as RemoteResult.Success
            val songs = remote.songs(size = 10, offset = 20) as RemoteResult.Success

            assertEquals("夜航", albums.value.single().name)
            assertEquals("月光", songs.value.single().title)
            assertEquals(-7.2, songs.value.single().replayGain?.trackGainDb ?: 0.0, 0.0001)
            assertEquals(-5.1, songs.value.single().replayGain?.albumGainDb ?: 0.0, 0.0001)
            assertTrue(requests.first { "getAlbumList2" in it }.contains("offset=50"))
            val songRequest = requests.first { "search3" in it }
            assertTrue(songRequest.contains("query="))
            assertTrue(songRequest.contains("songOffset=20"))
            assertTrue(songRequest.contains("artistCount=0"))
            assertTrue(requests.all { "p=" !in it && "t=" in it && "s=" in it })
        } finally {
            server.stop(0)
        }
    }
}

private fun com.sun.net.httpserver.HttpExchange.respond(body: String) {
    val bytes = body.toByteArray()
    responseHeaders.add("Content-Type", "application/json")
    sendResponseHeaders(200, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}
