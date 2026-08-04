package com.cleartune.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.io.Closeable
import java.io.File
import okhttp3.OkHttpClient

fun interface PlaybackRequestHeadersProvider {
    fun headersFor(uri: Uri): Map<String, String>
}

interface PlaybackRequestHeadersOwner {
    val playbackRequestHeadersProvider: PlaybackRequestHeadersProvider
}

@UnstableApi
class PlayerCache(
    context: Context,
    private val headersProvider: PlaybackRequestHeadersProvider = PlaybackRequestHeadersProvider { emptyMap() },
) : Closeable {
    private val appContext = context.applicationContext
    private val databaseProvider = StandaloneDatabaseProvider(appContext)
    private val cache = SimpleCache(
        File(context.cacheDir, "playback_streaming_cache"),
        LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
        databaseProvider,
    )

    fun dataSourceFactory(): DataSource.Factory {
        val remote = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(OkHttpClient()))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val local = DefaultDataSource.Factory(appContext)
        return RemoteOnlyDataSourceFactory(remote, local, headersProvider)
    }

    override fun close() {
        cache.release()
        databaseProvider.close()
    }

    companion object {
        const val MAX_CACHE_BYTES: Long = 512L * 1024L * 1024L
    }
}

@UnstableApi
private class RemoteOnlyDataSourceFactory(
    private val remoteFactory: DataSource.Factory,
    private val localFactory: DataSource.Factory,
    private val headersProvider: PlaybackRequestHeadersProvider,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = RoutingDataSource(remoteFactory, localFactory, headersProvider)
}

@UnstableApi
private class RoutingDataSource(
    private val remoteFactory: DataSource.Factory,
    private val localFactory: DataSource.Factory,
    private val headersProvider: PlaybackRequestHeadersProvider,
) : DataSource {
    private val listeners = mutableListOf<TransferListener>()
    private var delegate: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        check(delegate == null) { "Data source is already open" }
        val actualUri = PrivateMediaSourceRegistry.resolve(dataSpec.uri.toString())
            ?.let(Uri::parse)
            ?: dataSpec.uri
        val actualSpec = if (actualUri == dataSpec.uri) dataSpec else dataSpec.withUri(actualUri)
        val remote = actualUri.scheme.equals("http", true) || actualUri.scheme.equals("https", true)
        val selected = (if (remote) remoteFactory else localFactory).createDataSource()
        listeners.forEach(selected::addTransferListener)
        delegate = selected
        val request = if (remote) {
            actualSpec.withRequestHeaders(actualSpec.httpRequestHeaders + headersProvider.headersFor(actualUri))
        } else {
            actualSpec
        }
        return selected.open(request)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(delegate).read(buffer, offset, length)

    override fun getUri(): Uri? = delegate?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders.orEmpty()

    override fun close() {
        delegate?.close()
        delegate = null
    }
}
