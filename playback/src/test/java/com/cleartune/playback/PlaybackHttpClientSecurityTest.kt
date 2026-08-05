package com.cleartune.playback

import com.cleartune.core.contracts.WebDavCredential
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackHttpClientSecurityTest {
    @Test
    fun `playback transport rejects redirects before credentials can cross source boundaries`() {
        val client = securePlaybackHttpClient()

        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
    }

    @Test
    fun `playback uses exact source identity for challenge digest authentication`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse.Builder()
                    .code(401)
                    .addHeader(
                        "WWW-Authenticate",
                        "Digest realm=\"music\", nonce=\"n1\", algorithm=SHA-256, qop=\"auth\"",
                    )
                    .build(),
            )
            server.enqueue(MockResponse.Builder().code(200).body("audio").build())
            val resolvedIds = mutableListOf<String>()
            val client = securePlaybackHttpClient(
                PlaybackCredentialResolver { sourceId ->
                    resolvedIds += sourceId
                    PlaybackCredentialContext(
                        sourceId,
                        server.url("/dav/"),
                        WebDavCredential("alice", "secret".toCharArray()),
                    )
                },
            )
            val request = Request.Builder()
                .url(server.url("/dav/song.mp3"))
                .header(PLAYBACK_SOURCE_ID_HEADER, "source-b")
                .header(PLAYBACK_LOCATION_ID_HEADER, "location-b")
                .build()

            client.newCall(request).execute().use { response -> assertEquals(200, response.code) }

            assertEquals(listOf("source-b"), resolvedIds)
            server.takeRequest()
            assertTrue(requireNotNull(server.takeRequest().headers["Authorization"]).startsWith("Digest "))
        } finally {
            server.close()
        }
    }
}
