package com.cleartune.core.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlaybackCacheManagementTest {
    @Test fun settingsSharesCacheAndSkipsReaders() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cache = PlaybackCacheStore.get(context)
        assertSame(cache, PlaybackCacheStore.get(context))
        val key = "management-test-${UUID.randomUUID()}"
        val audio = ByteArray(4096) { 4 }
        val source = CacheDataSource.Factory().setCache(cache)
            .setUpstreamDataSourceFactory { ByteArrayDataSource(audio) }.createDataSource()
        try {
            source.open(DataSpec.Builder().setUri("https://example.test/audio").setKey(key).build())
            val buffer = ByteArray(1024)
            while (source.read(buffer, 0, buffer.size) >= 0) {}
        } finally { source.close() }
        PlaybackCacheStore.beginRead(key)
        try {
            PlaybackCacheStore.resize(context, 0)
            assertTrue(PlaybackCacheStore.clear(context).skipped > 0)
            assertTrue(cache.getCachedSpans(key).isNotEmpty())
        } finally { PlaybackCacheStore.endRead(key) }
        // Releasing the reader must finish a previously deferred shrink without a new download.
        assertTrue(cache.getCachedSpans(key).isEmpty())
        PlaybackCacheStore.resize(context, 512)
        assertEquals(0L, PlaybackCacheStore.clear(context).bytes)
    }
}
