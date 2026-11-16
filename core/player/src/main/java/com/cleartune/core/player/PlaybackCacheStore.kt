package com.cleartune.core.player

import android.content.Context
import android.os.StatFs
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.SimpleCache
import com.cleartune.core.datastore.DEFAULT_PLAYBACK_CACHE_SIZE_MB
import java.io.File
import kotlinx.coroutines.flow.first

/** Process-owned cache: settings and the playback service never open competing instances. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object PlaybackCacheStore {
    @Volatile var currentMediaKey: String? = null
    @Volatile var currentLocalUri: String? = null
    private var instance: SimpleCache? = null
    @Volatile private var limitMb = DEFAULT_PLAYBACK_CACHE_SIZE_MB
    private var evictor: ResizableLeastRecentlyUsedCacheEvictor? = null
    private val readers = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    @Synchronized fun get(context: Context): SimpleCache = instance ?: run {
        val capacity = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            com.cleartune.core.datastore.AppPreferences(context).settings.first().playbackCacheSizeMb
        }
        limitMb = capacity
        val policy = ResizableLeastRecentlyUsedCacheEvictor(capacity * 1_048_576L)
        evictor = policy
        SimpleCache(File(context.filesDir, PLAYBACK_CACHE_DIRECTORY_NAME), policy,
            StandaloneDatabaseProvider(context.applicationContext)).also { instance = it }
    }
    fun resize(context: Context, mb: Int) { val cache = get(context); synchronized(cache) { limitMb = mb; evictor!!.resize(cache, mb * 1_048_576L) } }
    fun size(context: Context): Long = get(context).cacheSpace
    fun beginRead(key: String) { readers.compute(key) { _, count -> (count ?: java.util.concurrent.atomic.AtomicInteger()).apply { incrementAndGet() } } }
    fun endRead(key: String) {
        readers.computeIfPresent(key) { _, count -> if (count.decrementAndGet() <= 0) null else count }
        trim()
    }
    fun trim() { val cache = instance ?: return; synchronized(cache) { evictor?.resize(cache, limitMb * 1_048_576L) } }
    fun isReading(key: String): Boolean {
        val current = currentMediaKey
        return readers.containsKey(key) || (current != null &&
            current.substringBeforeLast(":maxBitRate=") == key.substringBeforeLast(":maxBitRate="))
    }
    data class Cleared(val bytes: Long, val skipped: Int, val failed: Int)
    fun clear(context: Context): Cleared {
        val cache = get(context)
        var removed = 0L; var skipped = 0; var failed = 0
        synchronized(cache) {
            for (key in cache.keys.toList()) {
                if (isReading(key)) { skipped++; continue }
                val spans = cache.getCachedSpans(key).toList()
                for (span in spans) try { cache.removeSpan(span); removed += span.length } catch (_: Exception) { failed++ }
            }
        }
        return Cleared(removed, skipped, failed)
    }
    fun canWrite(context: Context) = StatFs(context.filesDir.path).availableBytes > 1_073_741_824L
}
