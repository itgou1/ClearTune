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
import com.cleartune.core.contracts.WebDavCredential
import com.cleartune.core.network.WebDavAuthenticator
import java.io.Closeable
import java.io.File
import okhttp3.Authenticator
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

internal const val PLAYBACK_SOURCE_ID_HEADER = "X-ClearTune-Playback-Source"
internal const val PLAYBACK_LOCATION_ID_HEADER = "X-ClearTune-Playback-Location"
private const val DEFAULT_CACHE_LIMIT_BYTES: Long = 512L * 1024L * 1024L

data class PlaybackCredentialContext(
    val sourceId: String,
    val baseUrl: HttpUrl,
    val credential: WebDavCredential,
)

fun interface PlaybackCredentialResolver {
    fun resolve(sourceId: String): PlaybackCredentialContext?
}

interface PlaybackCredentialResolverOwner {
    val playbackCredentialResolver: PlaybackCredentialResolver
}

data class PlaybackRuntimeSettings(
    val restoreQueue: Boolean = true,
    val pauseOnHeadphoneDisconnect: Boolean = true,
    val streamingCacheEnabled: Boolean = false,
    val backgroundPlayback: Boolean = false,
    val dynamicBackground: Boolean = true,
    val cacheLimitBytes: Long = DEFAULT_CACHE_LIMIT_BYTES,
)

fun interface PlaybackRuntimeSettingsProvider {
    fun snapshot(): PlaybackRuntimeSettings
}

interface PlaybackRuntimeSettingsOwner {
    val playbackRuntimeSettingsProvider: PlaybackRuntimeSettingsProvider
}

internal class PlaybackServicePolicy(private val settings: PlaybackRuntimeSettings) {
    val handleAudioBecomingNoisy: Boolean get() = settings.pauseOnHeadphoneDisconnect

    fun shouldStopOnTaskRemoved(playWhenReady: Boolean, mediaItemCount: Int): Boolean =
        !settings.backgroundPlayback || !playWhenReady || mediaItemCount == 0
}

internal fun cacheMaxBytesOrNull(settings: PlaybackRuntimeSettings): Long? =
    settings.cacheLimitBytes.coerceAtLeast(MIN_CACHE_BYTES).takeIf { settings.streamingCacheEnabled }

fun interface PlaybackRequestHeadersProvider {
    fun headersFor(uri: Uri): Map<String, String>
}

interface PlaybackRequestHeadersOwner {
    val playbackRequestHeadersProvider: PlaybackRequestHeadersProvider
}

@UnstableApi
class PlayerCache(
    context: Context,
    settings: PlaybackRuntimeSettings = PlaybackRuntimeSettings(),
    private val credentialResolver: PlaybackCredentialResolver = PlaybackCredentialResolver { null },
    private val headersProvider: PlaybackRequestHeadersProvider = PlaybackRequestHeadersProvider { emptyMap() },
) : Closeable {
    private val appContext = context.applicationContext
    private val cacheLimitBytes = cacheMaxBytesOrNull(settings)
    private val databaseProvider = cacheLimitBytes?.let { StandaloneDatabaseProvider(appContext) }
    private val cache = cacheLimitBytes?.let { maximumBytes ->
        SimpleCache(
            File(context.cacheDir, "playback_streaming_cache"),
            LeastRecentlyUsedCacheEvictor(maximumBytes),
            requireNotNull(databaseProvider),
        )
    }

    fun dataSourceFactory(): DataSource.Factory {
        val upstream = OkHttpDataSource.Factory(securePlaybackHttpClient(credentialResolver))
        val remote: DataSource.Factory = cache?.let {
            CacheDataSource.Factory()
                .setCache(it)
                .setUpstreamDataSourceFactory(upstream)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        } ?: upstream
        val local = DefaultDataSource.Factory(appContext)
        return RemoteOnlyDataSourceFactory(remote, local, headersProvider)
    }

    override fun close() {
        cache?.release()
        databaseProvider?.close()
    }
}

private const val MIN_CACHE_BYTES: Long = 64L * 1024L * 1024L

internal fun securePlaybackHttpClient(
    credentialResolver: PlaybackCredentialResolver = PlaybackCredentialResolver { null },
): OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false)
    .followSslRedirects(false)
    .addInterceptor { chain ->
        val request = chain.request()
        val sourceId = request.header(PLAYBACK_SOURCE_ID_HEADER)
        val locationId = request.header(PLAYBACK_LOCATION_ID_HEADER)
        val sanitized = request.newBuilder()
            .removeHeader(PLAYBACK_SOURCE_ID_HEADER)
            .removeHeader(PLAYBACK_LOCATION_ID_HEADER)
            .apply {
                if (sourceId != null) {
                    tag(PlaybackRequestIdentity::class.java, PlaybackRequestIdentity(sourceId, locationId))
                }
            }
            .build()
        chain.proceed(sanitized)
    }
    .authenticator(PlaybackAuthenticator(credentialResolver))
    .build()

private data class PlaybackRequestIdentity(val sourceId: String, val locationId: String?)

private class PlaybackAuthenticator(
    private val credentialResolver: PlaybackCredentialResolver,
) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        val identity = response.request.tag(PlaybackRequestIdentity::class.java) ?: return null
        val context = credentialResolver.resolve(identity.sourceId) ?: return null
        if (context.sourceId != identity.sourceId) {
            context.credential.password.fill('\u0000')
            return null
        }
        return try {
            WebDavAuthenticator(
                baseUrl = context.baseUrl,
                credentialProvider = { context.credential },
            ).authenticate(route, response)
        } finally {
            context.credential.password.fill('\u0000')
        }
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
        val source = PrivateMediaSourceRegistry.resolveEntry(dataSpec.uri.toString())
        val actualUri = source?.actualUri
            ?.let(Uri::parse)
            ?: dataSpec.uri
        val actualSpec = if (actualUri == dataSpec.uri) dataSpec else dataSpec.withUri(actualUri)
        val remote = actualUri.scheme.equals("http", true) || actualUri.scheme.equals("https", true)
        val selected = (if (remote) remoteFactory else localFactory).createDataSource()
        listeners.forEach(selected::addTransferListener)
        delegate = selected
        val request = if (remote) {
            val identityHeaders = buildMap {
                source?.sourceId?.let { put(PLAYBACK_SOURCE_ID_HEADER, it) }
                source?.locationId?.let { put(PLAYBACK_LOCATION_ID_HEADER, it) }
            }
            actualSpec.withRequestHeaders(
                actualSpec.httpRequestHeaders + headersProvider.headersFor(actualUri) + identityHeaders,
            )
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
