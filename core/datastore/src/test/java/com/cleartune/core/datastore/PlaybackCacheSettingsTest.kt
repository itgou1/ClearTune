package com.cleartune.core.datastore

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackCacheSettingsTest {
    @Test
    fun missingOrUnsupportedValueUsesDefault() {
        assertEquals(DEFAULT_PLAYBACK_CACHE_SIZE_MB, normalizedPlaybackCacheSizeMb(null))
        assertEquals(DEFAULT_PLAYBACK_CACHE_SIZE_MB, normalizedPlaybackCacheSizeMb(64))
    }

    @Test
    fun supportedValueIsPreserved() {
        PLAYBACK_CACHE_SIZE_OPTIONS_MB.forEach { size ->
            assertEquals(size, normalizedPlaybackCacheSizeMb(size))
        }
    }
}
