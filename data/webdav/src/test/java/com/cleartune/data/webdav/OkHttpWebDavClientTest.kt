package com.cleartune.data.webdav

import com.cleartune.core.model.MusicSource
import com.cleartune.core.model.SourceId
import com.cleartune.core.model.SourceType
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OkHttpWebDavClientTest {
    private lateinit var server: MockWebServer

    @Before fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After fun stopServer() {
        server.close()
    }

    @Test
    fun sends_one_depth_one_propfind_and_parses_entries() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(207)
                .addHeader("Content-Type", "application/xml")
                .body("""
                    <multistatus xmlns="DAV:"><response><href>/dav/song.mp3</href><propstat><prop><getcontentlength>4</getcontentlength></prop><status>HTTP/1.1 200 OK</status></propstat></response></multistatus>
                """.trimIndent())
                .build(),
        )
        val source = source(server.url("/dav/" ).toString())
        val client = OkHttpWebDavClient(OkHttpClient())

        val entries = client.list(source, server.url("/dav/"))
        val request = server.takeRequest()

        assertEquals(1, entries.size)
        assertEquals("PROPFIND", request.method)
        assertEquals("1", request.headers["Depth"])
        assertTrue(request.body?.utf8()?.contains("propfind") == true)
    }

    @Test
    fun reads_bounded_range_and_sends_exact_header() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(206)
                .addHeader("Content-Range", "bytes 2-5/10")
                .body("2345")
                .build(),
        )
        val source = source(server.url("/dav/").toString())
        val client = OkHttpWebDavClient(OkHttpClient())

        val response = client.readRange(source, server.url("/dav/song.mp3"), 2, 5, maxBytes = 4)

        assertEquals("2345", response.bytes.decodeToString())
        assertEquals("bytes=2-5", server.takeRequest().headers["Range"])
    }

    private fun source(baseUrl: String) = MusicSource(
        id = SourceId("source-1"),
        name = "Server",
        type = SourceType.WEBDAV,
        baseUrl = baseUrl,
        allowCleartext = true,
    )
}
