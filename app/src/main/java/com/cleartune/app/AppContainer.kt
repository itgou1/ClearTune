package com.cleartune.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import com.cleartune.core.contracts.CredentialStore
import com.cleartune.core.contracts.DownloadRepository
import com.cleartune.core.contracts.LibraryRepository
import com.cleartune.core.contracts.PlaybackLibraryRepository
import com.cleartune.core.contracts.SourceRepository
import com.cleartune.core.contracts.PlaylistRepository
import com.cleartune.core.contracts.SettingsRepository
import com.cleartune.core.contracts.WebDavCredential
import com.cleartune.core.model.AlbumId
import com.cleartune.core.model.DownloadCommand
import com.cleartune.core.model.DownloadSummary
import com.cleartune.core.model.LibraryHome
import com.cleartune.core.model.MusicSource
import com.cleartune.core.model.PlayableTrack
import com.cleartune.core.model.PlaylistId
import com.cleartune.core.model.PlaylistItemId
import com.cleartune.core.model.QueueItem
import com.cleartune.core.model.QueueItemId
import com.cleartune.core.model.QueueSnapshot
import com.cleartune.core.model.RepeatMode
import com.cleartune.core.model.SearchResults
import com.cleartune.core.model.SongQuery
import com.cleartune.core.model.SourceId
import com.cleartune.core.model.CredentialAlias
import com.cleartune.core.model.SourceType
import com.cleartune.core.model.TrackId
import com.cleartune.core.model.TrackSummary
import com.cleartune.core.model.AppSettings
import com.cleartune.core.model.ReducedMotionMode
import com.cleartune.core.model.SettingsCommand
import com.cleartune.core.model.ThemeMode
import com.cleartune.feature.playlists.PersistentPlaylistRepository
import com.cleartune.feature.playlists.PlaylistDetailsProvider
import com.cleartune.feature.playlists.PlaylistDetails
import com.cleartune.feature.playlists.PlaylistItemRecord
import com.cleartune.feature.playlists.PlaylistStorage
import com.cleartune.feature.settings.SettingsProductCommand
import com.cleartune.feature.settings.SettingsProductController
import com.cleartune.feature.settings.SettingsProductState
import com.cleartune.feature.settings.SettingsOperationState
import com.cleartune.playback.Media3PlaybackBackend
import com.cleartune.playback.PersistentQueueRepository
import com.cleartune.playback.PlaybackCoordinator
import com.cleartune.playback.PlaybackEnvironment
import com.cleartune.playback.QueueStorage
import com.cleartune.playback.PlaybackRequestHeadersProvider
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

class AppContainer(
    context: Context,
    val libraryRepository: LibraryRepository = EmptyLibraryRepository(),
    private val playbackLibraryRepository: PlaybackLibraryRepository =
        libraryRepository as? PlaybackLibraryRepository ?: EmptyPlaybackLibraryRepository,
    val sourceRepository: SourceRepository = EmptySourceRepository,
    val downloadRepository: DownloadRepository = EmptyDownloadRepository,
    val downloadCommandsAvailable: Boolean = downloadRepository !== EmptyDownloadRepository,
    private val credentialStore: CredentialStore = EmptyCredentialStore,
) {
    private val appContext = context.applicationContext
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val appPlaylistRepository = PersistentPlaylistRepository(
        storage = SharedPreferencesPlaylistStorage(appContext),
    )
    val playlistRepository: PlaylistRepository = appPlaylistRepository
    val playlistDetailsProvider: PlaylistDetailsProvider = appPlaylistRepository
    private val appSettingsRepository = AppSettingsRepository(SharedPreferencesSettingsStorage(appContext))
    val settingsRepository: SettingsRepository = appSettingsRepository
    val settingsProductController: SettingsProductController = appSettingsRepository
    val queueRepository = PersistentQueueRepository(SharedPreferencesQueueStorage(appContext))
    private val mediaBackend = Media3PlaybackBackend(appContext)
    val playbackRequestHeadersProvider = PlaybackRequestHeadersProvider { uri ->
        runBlocking(Dispatchers.IO) {
            val source = sourceRepository.observeSources().first().firstOrNull { candidate ->
                val baseUrl = candidate.baseUrl
                candidate.type == SourceType.WEBDAV &&
                    !baseUrl.isNullOrBlank() &&
                    uri.toString().startsWith(baseUrl, ignoreCase = true)
            }
            val credential = source?.credentialAlias?.let { credentialStore.get(it) }
            if (credential == null) emptyMap() else basicAuthorizationHeader(credential)
        }
    }
    val playbackGateway = PlaybackCoordinator(
        libraryRepository = playbackLibraryRepository,
        queueRepository = queueRepository,
        backend = mediaBackend,
        environment = PlaybackEnvironment(
            fileExists = ::fileExists,
            uriReadable = ::uriReadable,
            networkAvailable = ::networkAvailable,
        ),
    )

    init {
        applicationScope.launch { playbackGateway.syncQueue() }
        applicationScope.launch {
            var lastPositionWriteAt = 0L
            playbackGateway.state.collect { state ->
                val now = SystemClock.elapsedRealtime()
                if (!state.isPlaying || now - lastPositionWriteAt >= 5_000) {
                    queueRepository.updatePlaybackState(
                        positionMs = state.positionMs,
                        playWhenReady = state.isPlaying,
                        repeatMode = state.repeatMode,
                        shuffleEnabled = state.shuffleEnabled,
                    )
                    lastPositionWriteAt = now
                }
            }
        }
    }

    fun close() {
        applicationScope.cancel()
        mediaBackend.release()
    }

    private fun fileExists(rawUri: String): Boolean {
        val uri = Uri.parse(rawUri)
        val path = if (uri.scheme == "file") uri.path else rawUri
        return !path.isNullOrBlank() && File(path).isFile
    }

    private fun uriReadable(rawUri: String): Boolean = runCatching {
        val uri = Uri.parse(rawUri)
        when (uri.scheme) {
            "content" -> appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
            "file" -> uri.path?.let { File(it).isFile } ?: false
            else -> false
        }
    }.getOrDefault(false)

    private fun networkAvailable(): Boolean {
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

class EmptyLibraryRepository : LibraryRepository, PlaybackLibraryRepository {
    override fun observeLibraryHome(): Flow<LibraryHome> = flowOf(LibraryHome())
    override fun observeSongs(query: SongQuery): Flow<List<TrackSummary>> = flowOf(emptyList())
    override fun observeAlbumTracks(albumId: AlbumId): Flow<List<TrackSummary>> = flowOf(emptyList())
    override fun search(query: String): Flow<SearchResults> = flowOf(SearchResults())
    override suspend fun getPlayableTrack(trackId: TrackId): PlayableTrack? = null
}

private object EmptyPlaybackLibraryRepository : PlaybackLibraryRepository {
    override suspend fun getPlayableTrack(trackId: TrackId): PlayableTrack? = null
}

private object EmptySourceRepository : SourceRepository {
    override fun observeSources(): Flow<List<MusicSource>> = flowOf(emptyList())
    override suspend fun getSource(sourceId: SourceId): MusicSource? = null
}

private object EmptyDownloadRepository : DownloadRepository {
    override fun observeDownloads(): Flow<List<DownloadSummary>> = flowOf(emptyList())
    override suspend fun dispatch(command: DownloadCommand) = Unit
}

private object EmptyCredentialStore : CredentialStore {
    override suspend fun put(alias: CredentialAlias, credential: WebDavCredential) = Unit
    override suspend fun get(alias: CredentialAlias): WebDavCredential? = null
    override suspend fun delete(alias: CredentialAlias) = Unit
}

private fun basicAuthorizationHeader(credential: WebDavCredential): Map<String, String> {
    val bytes = "${credential.username}:${credential.password.concatToString()}".toByteArray(Charsets.UTF_8)
    return try {
        mapOf("Authorization" to "Basic ${Base64.encodeToString(bytes, Base64.NO_WRAP)}")
    } finally {
        bytes.fill(0)
        credential.password.fill('\u0000')
    }
}

private interface AppSettingsStorage {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}

private class SharedPreferencesSettingsStorage(context: Context) : AppSettingsStorage {
    private val preferences = context.getSharedPreferences("cleartune_settings", Context.MODE_PRIVATE)
    override fun getString(key: String): String? = preferences.getString(key, null)
    override fun putString(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }
}

private class AppSettingsRepository(
    private val storage: AppSettingsStorage,
) : SettingsRepository, SettingsProductController {
    private val mutex = Mutex()
    private val appSettings = MutableStateFlow(
        AppSettings(
            themeMode = storage.enum(THEME, ThemeMode.SYSTEM),
            reducedMotionMode = storage.enum(MOTION, ReducedMotionMode.SYSTEM),
        ),
    )
    override val settings: Flow<AppSettings> = appSettings
    private val productState = MutableStateFlow(
        SettingsProductState(
            restoreQueue = storage.boolean(RESTORE_QUEUE, true),
            pauseOnHeadphoneDisconnect = storage.boolean(HEADPHONE_PAUSE, true),
            offlineCacheEnabled = storage.boolean(OFFLINE_CACHE, false),
            backgroundPlayback = storage.boolean(BACKGROUND_PLAYBACK, false),
            dynamicBackground = storage.boolean(DYNAMIC_BACKGROUND, true),
            cacheLimitMb = storage.getString(CACHE_LIMIT)?.toIntOrNull()?.coerceIn(64, 8_192) ?: 512,
        ),
    )
    override val productSettings: Flow<SettingsProductState> = productState

    override suspend fun update(command: SettingsCommand) = mutex.withLock {
        appSettings.value = when (command) {
            is SettingsCommand.SetTheme -> appSettings.value.copy(themeMode = command.mode)
                .also { storage.putString(THEME, command.mode.name) }
            is SettingsCommand.SetReducedMotion -> appSettings.value.copy(reducedMotionMode = command.mode)
                .also { storage.putString(MOTION, command.mode.name) }
        }
    }

    override suspend fun dispatch(command: SettingsProductCommand) = mutex.withLock {
        productState.value = when (command) {
            is SettingsProductCommand.SetRestoreQueue -> productState.value.copy(restoreQueue = command.enabled)
                .persist(RESTORE_QUEUE, command.enabled)
            is SettingsProductCommand.SetPauseOnHeadphoneDisconnect ->
                productState.value.copy(pauseOnHeadphoneDisconnect = command.enabled)
                    .persist(HEADPHONE_PAUSE, command.enabled)
            is SettingsProductCommand.SetOfflineCacheEnabled -> productState.value.copy(offlineCacheEnabled = command.enabled)
                .persist(OFFLINE_CACHE, command.enabled)
            is SettingsProductCommand.SetBackgroundPlayback -> productState.value.copy(backgroundPlayback = command.enabled)
                .persist(BACKGROUND_PLAYBACK, command.enabled)
            is SettingsProductCommand.SetDynamicBackground -> productState.value.copy(dynamicBackground = command.enabled)
                .persist(DYNAMIC_BACKGROUND, command.enabled)
            is SettingsProductCommand.SetCacheLimitMb -> {
                val value = command.megabytes.coerceIn(64, 8_192)
                productState.value.copy(cacheLimitMb = value).also { storage.putString(CACHE_LIMIT, "$value") }
            }
            SettingsProductCommand.ScanLibrary,
            SettingsProductCommand.CleanUpCache,
            SettingsProductCommand.OpenLicenses,
            -> {
                val unavailable = when (command) {
                    SettingsProductCommand.ScanLibrary -> productState.value.scanLibrary
                    SettingsProductCommand.CleanUpCache -> productState.value.cleanUpCache
                    SettingsProductCommand.OpenLicenses -> productState.value.openLicenses
                } as? SettingsOperationState.Unavailable
                check(unavailable == null) { unavailable?.reason ?: "Operation unavailable" }
                error("Operation adapter is not bound")
            }
        }
    }

    private fun SettingsProductState.persist(key: String, value: Boolean): SettingsProductState =
        also { storage.putString(key, value.toString()) }

    private companion object {
        const val THEME = "theme"
        const val MOTION = "motion"
        const val RESTORE_QUEUE = "restore_queue"
        const val HEADPHONE_PAUSE = "headphone_pause"
        const val OFFLINE_CACHE = "offline_cache"
        const val BACKGROUND_PLAYBACK = "background_playback"
        const val DYNAMIC_BACKGROUND = "dynamic_background"
        const val CACHE_LIMIT = "cache_limit_mb"
    }
}

private inline fun <reified T : Enum<T>> AppSettingsStorage.enum(key: String, default: T): T =
    enumValues<T>().firstOrNull { it.name == getString(key) } ?: default

private fun AppSettingsStorage.boolean(key: String, default: Boolean): Boolean =
    getString(key)?.toBooleanStrictOrNull() ?: default

private class SharedPreferencesQueueStorage(context: Context) : QueueStorage {
    private val preferences = context.getSharedPreferences("cleartune_queue", Context.MODE_PRIVATE)

    override fun load(): QueueSnapshot? = preferences.getString(SNAPSHOT_KEY, null)?.let { raw ->
        runCatching {
            val json = JSONObject(raw)
            val itemsJson = json.getJSONArray("items")
            val items = buildList {
                repeat(itemsJson.length()) { index ->
                    val item = itemsJson.getJSONObject(index)
                    add(QueueItem(QueueItemId(item.getString("id")), TrackId(item.getString("trackId"))))
                }
            }
            QueueSnapshot(
                items = items,
                currentIndex = json.optInt("currentIndex", -1).let { index ->
                    if (items.isEmpty()) -1 else index.coerceIn(items.indices)
                },
                positionMs = json.optLong("positionMs", 0).coerceAtLeast(0),
                playWhenReady = json.optBoolean("playWhenReady", false),
                repeatMode = runCatching { RepeatMode.valueOf(json.optString("repeatMode")) }
                    .getOrDefault(RepeatMode.OFF),
                shuffleEnabled = json.optBoolean("shuffleEnabled", false),
            )
        }.getOrNull()
    }

    override fun save(snapshot: QueueSnapshot) {
        val items = JSONArray().apply {
            snapshot.items.forEach { item ->
                put(JSONObject().put("id", item.id.value).put("trackId", item.trackId.value))
            }
        }
        val json = JSONObject()
            .put("items", items)
            .put("currentIndex", snapshot.currentIndex)
            .put("positionMs", snapshot.positionMs)
            .put("playWhenReady", snapshot.playWhenReady)
            .put("repeatMode", snapshot.repeatMode.name)
            .put("shuffleEnabled", snapshot.shuffleEnabled)
        preferences.edit().putString(SNAPSHOT_KEY, json.toString()).apply()
    }

    private companion object { const val SNAPSHOT_KEY = "snapshot" }
}

private class SharedPreferencesPlaylistStorage(context: Context) : PlaylistStorage {
    private val preferences = context.getSharedPreferences("cleartune_playlists", Context.MODE_PRIVATE)

    override fun load(): List<PlaylistDetails> = preferences.getString(SNAPSHOT_KEY, null)?.let { raw ->
        runCatching {
            val array = JSONArray(raw)
            buildList {
                repeat(array.length()) { index ->
                    val playlistJson = array.getJSONObject(index)
                    val itemArray = playlistJson.getJSONArray("items")
                    val items = buildList {
                        repeat(itemArray.length()) { itemIndex ->
                            val itemJson = itemArray.getJSONObject(itemIndex)
                            add(
                                PlaylistItemRecord(
                                    PlaylistItemId(itemJson.getString("id")),
                                    TrackId(itemJson.getString("trackId")),
                                ),
                            )
                        }
                    }
                    add(
                        PlaylistDetails(
                            PlaylistId(playlistJson.getString("id")),
                            playlistJson.getString("name"),
                            items,
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    } ?: emptyList()

    override fun save(playlists: List<PlaylistDetails>) {
        val array = JSONArray().apply {
            playlists.forEach { playlist ->
                val items = JSONArray().apply {
                    playlist.items.forEach { item ->
                        put(JSONObject().put("id", item.id.value).put("trackId", item.trackId.value))
                    }
                }
                put(
                    JSONObject()
                        .put("id", playlist.id.value)
                        .put("name", playlist.name)
                        .put("items", items),
                )
            }
        }
        preferences.edit().putString(SNAPSHOT_KEY, array.toString()).apply()
    }

    private companion object { const val SNAPSHOT_KEY = "snapshot" }
}
