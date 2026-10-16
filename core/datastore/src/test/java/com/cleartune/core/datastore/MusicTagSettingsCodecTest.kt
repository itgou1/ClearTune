package com.cleartune.core.datastore

import com.cleartune.core.model.*
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class MusicTagSettingsCodecTest {
    @Test fun migratesLegacyHttpWithoutChangingConsentOrPaths() {
        val raw = listOf("http://example.test:8002", "admin", "secret", "/music", "/app/media", "netease", "true")
            .joinToString("\n") { Base64.getEncoder().encodeToString(it.toByteArray()) }
        val decoded = MusicTagSettingsCodec.decode(raw)
        assertEquals(MusicTagConnectionMode.DIRECT, decoded.connectionMode)
        assertTrue(decoded.allowHttp)
        assertEquals("/music", decoded.sourceRoot)
        assertEquals("secret", decoded.password)
        assertEquals(decoded, MusicTagSettingsCodec.decode(MusicTagSettingsCodec.encode(decoded)))
    }

    @Test fun roundTripsBothModesAndMultilineKeys() {
        val settings = MusicTagSettings("https://example.test", "用户", "tag-password", connectionMode = MusicTagConnectionMode.SSH,
            ssh = MusicTagSshSettings("ssh.example.test", 2222, "tunnel", MusicTagSshAuth.PRIVATE_KEY,
                "ssh-password", "-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----\n", "口令",
                "http://127.0.0.1:8002/tag/", "a2V5", "ssh.example.test:2222"))
        assertEquals(settings, MusicTagSettingsCodec.decode(MusicTagSettingsCodec.encode(settings)))
        val direct = settings.copy(connectionMode = MusicTagConnectionMode.DIRECT)
        assertEquals(settings.ssh, MusicTagSettingsCodec.decode(MusicTagSettingsCodec.encode(direct)).ssh)
        assertFalse(settings.toString().contains("password"))
        assertFalse(settings.ssh.toString().contains("口令"))
    }

    @Test fun rejectsUnknownOrTruncatedFormat() {
        assertTrue(runCatching { MusicTagSettingsCodec.decode("v3\nabc") }.isFailure)
        assertTrue(runCatching { MusicTagSettingsCodec.decode("v2\n") }.isFailure)
    }
}
