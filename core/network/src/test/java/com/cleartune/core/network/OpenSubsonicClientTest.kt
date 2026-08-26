package com.cleartune.core.network

import com.cleartune.core.model.ConnectionResult
import com.cleartune.core.model.ServerCredentials
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSubsonicClientTest {
    @Test
    fun connectsAndReadsServerCapabilities() = runBlocking {
        val requests = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/rest/ping.view") { exchange ->
                requests += exchange.requestURI.toString()
                val body = """
                    {"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","serverVersion":"0.63.2","openSubsonic":true}}
                """.trimIndent().toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            createContext("/rest/getOpenSubsonicExtensions.view") { exchange ->
                requests += exchange.requestURI.toString()
                val body = """
                    {"subsonic-response":{"status":"ok","version":"1.16.1","openSubsonicExtensions":[{"name":"songLyrics","versions":[1]}]}}
                """.trimIndent().toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            val result = OpenSubsonicClient().connect(
                ServerCredentials(
                    baseUrl = "http://127.0.0.1:${server.address.port}",
                    username = "alice",
                    password = "secret",
                    allowInsecureHttp = true,
                ),
            )
            assertTrue(result is ConnectionResult.Success)
            val profile = (result as ConnectionResult.Success).profile
            assertEquals("navidrome", profile.serverType)
            assertEquals(setOf("songLyrics"), profile.extensions)
            assertTrue(requests.all { "p=" !in it })
            assertTrue(requests.all { "t=" in it && "s=" in it })
        } finally {
            server.stop(0)
        }
    }
}
