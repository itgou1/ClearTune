package com.cleartune.core.network

import com.cleartune.core.model.ServerCredentials
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class InternetRadioTest {
    @Test fun readsAuthenticatedStationListWithoutRewritingStreamTokens() = runBlocking {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        var query = ""
        var payload = """{"subsonic-response":{"status":"ok","internetRadioStations":{"internetRadioStation":[{"id":"cnr","name":"音乐之声","streamUrl":"https://radio.example/live?token=a%2Bb&station=3","homePageUrl":"https://example.com"}]}}}"""
        server.createContext("/rest/getInternetRadioStations.view") { exchange ->
            query = exchange.requestURI.rawQuery
            val bytes = payload.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val remote = LibraryRemoteDataSource(OpenSubsonicApiFactory().authorized(
                ServerCredentials("http://127.0.0.1:${server.address.port}", "radio-user", "secret", true),
            ))
            val station = (remote.radioStations() as RemoteResult.Success).value.single()
            assertEquals("https://radio.example/live?token=a%2Bb&station=3", station.streamUrl)
            assertEquals("音乐之声", station.asSong().title)
            assertTrue(station.asSong().isRadio)
            assertEquals("radio:cnr", station.playbackId)
            assertTrue(query.contains("u=radio-user"))
            assertFalse(query.contains("secret"))
            payload = """{"subsonic-response":{"status":"ok","internetRadioStations":{}}}"""
            assertTrue((remote.radioStations() as RemoteResult.Success).value.isEmpty())
            payload = """{"subsonic-response":{"status":"failed","error":{"code":40,"message":"Unauthorized"}}}"""
            assertTrue(remote.radioStations() is RemoteResult.Failure)
        } finally { server.stop(0) }
    }
}
