package com.cleartune.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAddressNormalizerTest {
    @Test
    fun addsTrailingSlashAndNormalizesScheme() {
        assertEquals(
            "https://music.example.com/navidrome/",
            ServerAddressNormalizer.normalize(
                "HTTPS://music.example.com/navidrome/",
                allowInsecureHttp = false,
            ).getOrThrow(),
        )
    }

    @Test
    fun blocksHttpByDefault() {
        assertTrue(
            ServerAddressNormalizer.normalize(
                "http://music.example.com",
                allowInsecureHttp = false,
            ).isFailure,
        )
    }

    @Test
    fun allowsHttpForExplicitDevelopmentServer() {
        assertEquals(
            "http://203.0.113.10:4533/",
            ServerAddressNormalizer.normalize(
                "http://203.0.113.10:4533",
                allowInsecureHttp = true,
            ).getOrThrow(),
        )
    }
}
