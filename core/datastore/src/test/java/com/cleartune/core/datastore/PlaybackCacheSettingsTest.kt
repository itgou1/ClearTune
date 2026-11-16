package com.cleartune.core.datastore

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackCacheSettingsTest {
    @Test
    fun optionsStartAt512MbAndEndAt32Gb() {
        assertEquals(listOf(512, 1_024, 2_048, 4_096, 8_192, 16_384, 32_768), PLAYBACK_CACHE_SIZE_OPTIONS_MB)
        assertEquals(512, DEFAULT_PLAYBACK_CACHE_SIZE_MB)
    }

    @Test
    fun legacySmallCacheValuesUse512Mb() {
        assertEquals(512, normalizedPlaybackCacheSizeMb(128))
        assertEquals(512, normalizedPlaybackCacheSizeMb(256))
    }

    @Test
    fun missingOrUnsupportedValueUsesDefault() {
        assertEquals(DEFAULT_PLAYBACK_CACHE_SIZE_MB, normalizedPlaybackCacheSizeMb(null))
        assertEquals(DEFAULT_PLAYBACK_CACHE_SIZE_MB, normalizedPlaybackCacheSizeMb(64))
    }

    @Test
    fun customValuesAndBounds() {
        assertEquals(1536, normalizedPlaybackCacheSizeMb(1536))
        assertEquals(32768, normalizedPlaybackCacheSizeMb(32768))
        assertEquals(512, normalizedPlaybackCacheSizeMb(32769))
        assertEquals(512, normalizedPlaybackCacheSizeMb(-1))
        assertEquals(34_359_738_368L, normalizedPlaybackCacheSizeMb(32768) * 1_048_576L)
    }

    @Test
    fun supportedValueIsPreserved() {
        PLAYBACK_CACHE_SIZE_OPTIONS_MB.forEach { size ->
            assertEquals(size, normalizedPlaybackCacheSizeMb(size))
        }
    }
}
