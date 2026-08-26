package com.cleartune.core.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkRedactorTest {
    @Test
    fun removesAuthenticationQueryValues() {
        val redacted = NetworkRedactor.redact(
            "https://music.test/rest/ping?u=alice&t=secret&s=salt&p=enc:123&v=1.16.1",
        )
        assertFalse(redacted.contains("alice"))
        assertFalse(redacted.contains("secret"))
        assertFalse(redacted.contains("salt"))
        assertFalse(redacted.contains("enc:123"))
        assertTrue(redacted.contains("v=1.16.1"))
    }
}
