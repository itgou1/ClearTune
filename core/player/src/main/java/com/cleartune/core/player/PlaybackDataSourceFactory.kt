package com.cleartune.core.player

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata

/** Uses the disk cache only for network streams; downloaded and content URIs stay direct. */
@UnstableApi
internal class PlaybackDataSourceFactory(
    private val cache: Cache,
    private val directFactory: DataSource.Factory,
    private val reviseKey: (String) -> String = { it },
    private val canWrite: () -> Boolean = { true },
    private val isOffline: () -> Boolean,
) : DataSource.Factory {
    private val cachedFactory = CacheDataSource.Factory().setCache(cache)
        .setUpstreamDataSourceFactory(directFactory)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        .setCacheWriteDataSinkFactory {
            val sink = androidx.media3.datasource.cache.CacheDataSink(cache, 2 * 1024 * 1024L)
            object : androidx.media3.datasource.DataSink {
                var writing = false
                override fun open(spec: DataSpec) { if (canWrite()) { sink.open(spec); writing = true } }
                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    if (writing && !canWrite()) { writing = false; sink.close() }
                    if (writing) sink.write(buffer, offset, length)
                }
                override fun close() { if (writing) { writing = false; sink.close() } }
            }
        }
    private val offlineFactory = CacheDataSource.Factory().setCache(cache)
        .setCacheWriteDataSinkFactory(null)

    override fun createDataSource(): DataSource = RoutingDataSource(
        cache = cache,
        cached = cachedFactory.createDataSource(),
        offline = offlineFactory.createDataSource(),
        direct = directFactory.createDataSource(),
        isOffline = isOffline,
        reviseKey = reviseKey,
    )
}

@UnstableApi
private class RoutingDataSource(
    private val cache: Cache,
    private val cached: DataSource,
    private val offline: DataSource,
    private val direct: DataSource,
    private val isOffline: () -> Boolean,
    private val reviseKey: (String) -> String,
) : DataSource {
    private var active: DataSource? = null
    private var resolved = false
    private var requestedKey: String? = null
    private var resolvedKey: String? = null
    private var alternativeQuality = false
    private var registeredRead = false

    override fun addTransferListener(transferListener: TransferListener) {
        cached.addTransferListener(transferListener)
        offline.addTransferListener(transferListener)
        direct.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        check(active == null) { "DataSource is already open" }
        if (!dataSpec.uri.isHttpStream() || dataSpec.key?.startsWith("cleartune:radio:") == true) {
            active = direct
            return direct.open(dataSpec)
        }
        val currentKey = dataSpec.key?.let(reviseKey)
        val disconnected = isOffline()
        if (!resolved || requestedKey != dataSpec.key) {
            requestedKey = dataSpec.key
            // Resolve once, before extraction. A seek/retry must never switch encoded
            // formats underneath an existing extractor or reuse another format's offsets.
            resolvedKey = if (disconnected && dataSpec.position == 0L) {
                currentKey?.let { offlinePlaybackCacheKey(cache, it) }
            } else currentKey
            alternativeQuality = resolvedKey != currentKey
            resolved = true
        }
        // An alternative has different bytes from the requested URL. Never refill its
        // cache holes from that URL, even if connectivity returns during playback.
        active = if (disconnected || alternativeQuality) offline else cached
        if (!alternativeQuality && resolvedKey != currentKey) throw java.io.IOException("Audio file changed; reopen playback")
        resolvedKey?.let { PlaybackCacheStore.beginRead(it); registeredRead = true }
        try { return active!!.open(dataSpec.buildUpon().setKey(resolvedKey).build()) }
        catch (error: Exception) { close(); throw error }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(active) { "DataSource is not open" }.read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        active?.responseHeaders ?: emptyMap()

    override fun close() {
        val source = active
        active = null
        try { source?.close() } finally {
            if (registeredRead) { registeredRead = false; resolvedKey?.let(PlaybackCacheStore::endRead) }
        }
    }
}

private fun Uri.isHttpStream(): Boolean = scheme.equals("http", true) || scheme.equals("https", true)

/** Only complete variants of the same server/account/song can replace the requested quality. */
@UnstableApi
internal fun offlinePlaybackCacheKey(cache: Cache, requested: String): String {
    fun complete(key: String): Boolean {
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        return length > 0 && cache.isCached(key, 0, length)
    }
    if (complete(requested)) return requested
    if (!requested.startsWith("cleartune:v2:") || ":maxBitRate=" !in requested) return requested
    val identity = requested.substringBeforeLast(":maxBitRate=")
    return cache.keys.asSequence()
        .filter { it.substringBeforeLast(":maxBitRate=") == identity && complete(it) }
        .sorted()
        .maxByOrNull {
            it.substringAfterLast(":maxBitRate=").substringBefore(":format=")
                .toIntOrNull() ?: Int.MAX_VALUE
        } ?: requested
}
