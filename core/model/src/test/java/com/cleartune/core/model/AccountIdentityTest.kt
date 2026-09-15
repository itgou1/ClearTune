package com.cleartune.core.model

import org.junit.Assert.*
import org.junit.Test

class AccountIdentityTest {
    @Test fun serverAndUserBothDefineTheNamespace() {
        val alice = accountStorageKey("https://music.example/", "alice")
        assertNotEquals(alice, accountStorageKey("https://music.example/", "bob"))
        assertNotEquals(alice, accountStorageKey("https://other.example/", "alice"))
        assertNotEquals(alice, accountStorageKey("https://music.example/navidrome/", "alice"))
        assertNotEquals(alice, accountStorageKey("http://music.example/", "alice"))
        assertNotEquals(alice, accountStorageKey("https://music.example/", "Alice"))
        assertTrue(alice.matches(Regex("[0-9a-f]{64}")))
    }

    @Test fun equivalentServerAddressesKeepTheSameData() {
        assertEquals(accountStorageKey("https://music.example", "alice"),
            accountStorageKey("HTTPS://MUSIC.EXAMPLE:443/", "alice"))
        assertEquals(accountStorageKey("http://music.example/navidrome/", "alice"),
            accountStorageKey("http://music.example:80/navidrome", "alice"))
    }
}
