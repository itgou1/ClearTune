package com.cleartune.core.player

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/** Uses the disk cache only for network streams; downloaded and content URIs stay direct. */
@UnstableApi
internal class PlaybackDataSourceFactory(
    private val cachedFactory: DataSource.Factory,
    private val directFactory: DataSource.Factory,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = RoutingDataSource(
        cached = cachedFactory.createDataSource(),
        direct = directFactory.createDataSource(),
    )
}

@UnstableApi
private class RoutingDataSource(
    private val cached: DataSource,
    private val direct: DataSource,
) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        cached.addTransferListener(transferListener)
        direct.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        check(active == null) { "DataSource is already open" }
        active = if (dataSpec.uri.isHttpStream()) cached else direct
        return active!!.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(active) { "DataSource is not open" }.read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        active?.responseHeaders ?: emptyMap()

    override fun close() {
        val source = active
        active = null
        source?.close()
    }
}

private fun Uri.isHttpStream(): Boolean = scheme.equals("http", true) || scheme.equals("https", true)
