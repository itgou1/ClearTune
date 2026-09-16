package com.cleartune.app.artwork

import com.cleartune.core.model.accountStorageKey
import org.junit.Assert.*
import org.junit.Test

class ArtworkIdentityTest {
    @Test fun accountsServersAndSizesNeverShareEntries() {
        val alice = accountStorageKey("https://music.example", "alice")
        val bob = accountStorageKey("https://music.example", "bob")
        val other = accountStorageKey("https://other.example", "alice")
        val key = artworkCacheKey(alice, "same-cover", 192)
        assertNotEquals(key, artworkCacheKey(bob, "same-cover", 192))
        assertNotEquals(key, artworkCacheKey(other, "same-cover", 192))
        assertNotEquals(key, artworkCacheKey(alice, "same-cover", 768))
        assertEquals(key, artworkCacheKey(alice, "same-cover", 192))
        assertFalse(key.contains("alice"))
    }

    @Test fun untrustedCoverIdsBecomeSafeDistinctFileNames() {
        val name = artworkFileName("../../private/song?size=768")
        assertTrue(name.matches(Regex("[a-f0-9]{64}\\.img")))
        assertNotEquals(name, artworkFileName("../../private/song?size=192"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidAccountNamespaceIsRejected() { artworkCacheKey("../alice", "cover", 192) }
}
