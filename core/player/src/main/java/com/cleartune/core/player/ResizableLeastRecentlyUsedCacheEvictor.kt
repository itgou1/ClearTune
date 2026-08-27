package com.cleartune.core.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.TreeSet

/** Media3's LRU policy with a limit that can follow the user's setting at runtime. */
@UnstableApi
internal class ResizableLeastRecentlyUsedCacheEvictor(initialMaxBytes: Long) : CacheEvictor {
    private val stateLock = Any()
    private val leastRecentlyUsed = TreeSet<CacheSpan> { left, right ->
        when {
            left.lastTouchTimestamp < right.lastTouchTimestamp -> -1
            left.lastTouchTimestamp > right.lastTouchTimestamp -> 1
            else -> left.compareTo(right)
        }
    }
    private var maxBytes = initialMaxBytes.coerceAtLeast(0)
    private var currentSize = 0L

    override fun requiresCacheSpanTouches(): Boolean = true

    override fun onCacheInitialized() = Unit

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != C.LENGTH_UNSET.toLong()) evictCache(cache, length)
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        synchronized(stateLock) {
            if (leastRecentlyUsed.add(span)) currentSize += span.length
        }
        evictCache(cache, 0)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        synchronized(stateLock) {
            if (leastRecentlyUsed.remove(span)) currentSize -= span.length
        }
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        synchronized(stateLock) {
            if (leastRecentlyUsed.remove(oldSpan)) currentSize -= oldSpan.length
            if (leastRecentlyUsed.add(newSpan)) currentSize += newSpan.length
        }
        evictCache(cache, 0)
    }

    fun resize(cache: Cache, newMaxBytes: Long) {
        synchronized(stateLock) { maxBytes = newMaxBytes.coerceAtLeast(0) }
        evictCache(cache, 0)
    }

    private fun evictCache(cache: Cache, requiredSpace: Long) {
        while (true) {
            val oldest = synchronized(stateLock) {
                leastRecentlyUsed.firstOrNull()
                    ?.takeIf { currentSize + requiredSpace > maxBytes }
            } ?: return
            cache.removeSpan(oldest)
        }
    }
}
