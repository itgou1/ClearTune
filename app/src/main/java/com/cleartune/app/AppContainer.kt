package com.cleartune.app

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import com.cleartune.core.contracts.CredentialStore
import com.cleartune.core.contracts.DownloadRepository
import com.cleartune.core.contracts.LibraryRepository
import com.cleartune.core.contracts.PlaylistRepository
import com.cleartune.core.contracts.QueueRepository
import com.cleartune.core.contracts.SettingsRepository
import com.cleartune.core.contracts.SourceRepository
import com.cleartune.core.contracts.WebDavCredential
import com.cleartune.core.database.ClearTuneDatabase
import com.cleartune.core.database.RoomLibraryRepository
import com.cleartune.core.database.RoomFavoritesRepository
import com.cleartune.core.database.RoomPlaylistRepository
import com.cleartune.core.database.RoomQueueRepository
import com.cleartune.core.database.RoomSettingsRepository
import com.cleartune.core.model.DownloadState
import com.cleartune.core.model.DownloadId
import com.cleartune.core.model.QueueCommand
import com.cleartune.core.model.SongQuery
import com.cleartune.core.model.SourceId
import com.cleartune.core.model.SourceType
import com.cleartune.core.model.TrackId
import com.cleartune.core.network.WebDavAuthenticator
import com.cleartune.core.network.WebDavUrlPolicy
import com.cleartune.data.download.DownloadCoordinator
import com.cleartune.data.download.DownloadTransfer
import com.cleartune.data.download.DownloadWorkerHost
import com.cleartune.data.download.DownloadWorkerRunner
import com.cleartune.data.download.DownloadNetworkPolicyProvider
import com.cleartune.data.download.ProductionDownloadWorkerRunner
import com.cleartune.data.download.WorkManagerDownloadScheduler
import com.cleartune.data.download.asExecutor
import com.cleartune.data.local.AndroidMediaStoreGateway
import com.cleartune.data.local.AudioPermissionPolicy
import com.cleartune.data.local.LocalScanCoordinator
import com.cleartune.data.local.LocalScanScheduler
import com.cleartune.data.local.LocalScanWorkerFactory
import com.cleartune.data.local.LocalScanWorkerRunner
import com.cleartune.data.webdav.AndroidKeystoreCredentialCipher
import com.cleartune.data.webdav.DurableWebDavSyncRunner
import com.cleartune.data.webdav.EmbeddedArtworkCache
import com.cleartune.data.webdav.EncryptedCredentialStore
import com.cleartune.data.webdav.OkHttpWebDavClient
import com.cleartune.data.webdav.RangeWebDavMetadataEnricher
import com.cleartune.data.webdav.WebDavRangeReader
import com.cleartune.data.webdav.SharedPreferencesCredentialBlobStore
import com.cleartune.data.webdav.WebDavConnectionProbe
import com.cleartune.data.webdav.WebDavSourceManager
import com.cleartune.data.webdav.WebDavSyncEngine
import com.cleartune.data.webdav.WebDavSyncRunner
import com.cleartune.data.webdav.WebDavSyncWorkerHost
import com.cleartune.data.webdav.WorkManagerWebDavSyncScheduler
import com.cleartune.feature.downloads.DownloadTitleResolver
import com.cleartune.feature.library.LibraryBrowsePort
import com.cleartune.feature.playlists.PlaylistDetailsProvider
import com.cleartune.feature.settings.SettingsProductController
import com.cleartune.feature.sources.SourceController
import com.cleartune.playback.LibrarySessionCatalog
import com.cleartune.playback.Media3PlaybackBackend
import com.cleartune.playback.PlaybackCoordinator
import com.cleartune.playback.PlaybackCredentialContext
import com.cleartune.playback.PlaybackCredentialResolver
import com.cleartune.playback.PlaybackEnvironment
import com.cleartune.playback.PlaybackRuntimeSettings
import com.cleartune.playback.PlaybackRuntimeSettingsProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient

class AppContainer(context: Context) : DownloadWorkerHost, WebDavSyncWorkerHost {
    private val appContext = context.applicationContext
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val database = ClearTuneDatabase.build(appContext)
    private val roomLibraryRepository = RoomLibraryRepository(database)
    private val roomPlaylistRepository = RoomPlaylistRepository(database)
    val favoritesRepository = RoomFavoritesRepository(database)
    private val roomQueueRepository = RoomQueueRepository(database)
    private val baseHttpClient = OkHttpClient.Builder().build()

    val libraryRepository: LibraryRepository = roomLibraryRepository
    val sourceRepository: SourceRepository = roomLibraryRepository
    val sourceSyncStatus = RoomSourceSyncStatusAdapter(database)
    val playlistRepository: PlaylistRepository = roomPlaylistRepository
    val playlistDetailsProvider: PlaylistDetailsProvider = RoomPlaylistDetailsAdapter(database, roomPlaylistRepository)
    val libraryBrowsePort: LibraryBrowsePort = RoomLibraryBrowseAdapter(roomLibraryRepository, roomLibraryRepository)
    val settingsRepository: SettingsRepository = RoomSettingsRepository(database)
    val queueRepository: RoomPlaybackQueueAdapter = RoomPlaybackQueueAdapter(database, roomQueueRepository)

    val credentialStore: CredentialStore = EncryptedCredentialStore(
        AndroidKeystoreCredentialCipher(),
        SharedPreferencesCredentialBlobStore(appContext),
    )

    private val webDavClient = OkHttpWebDavClient(baseHttpClient, credentialStore)
    private val webDavPersistence = RoomWebDavPersistenceAdapter(
        database,
        sourceRepository,
        SharedPreferencesWebDavCheckpointStore(appContext),
    )
    private val webDavSyncScheduler = WorkManagerWebDavSyncScheduler(appContext)
    private val artworkCache = EmbeddedArtworkCache(File(appContext.filesDir, "webdav_artwork_cache"))
    private val webDavSourceManager = WebDavSourceManager(
        connectionProbe = WebDavConnectionProbe { source, credential ->
            val temporaryStore = object : CredentialStore {
                override suspend fun put(alias: com.cleartune.core.model.CredentialAlias, credential: WebDavCredential) = Unit
                override suspend fun get(alias: com.cleartune.core.model.CredentialAlias) =
                    WebDavCredential(credential.username, credential.password.copyOf())
                override suspend fun delete(alias: com.cleartune.core.model.CredentialAlias) = Unit
            }
            val probe = OkHttpWebDavClient(baseHttpClient, temporaryStore)
            val base = WebDavUrlPolicy.normalizeBaseUrl(requireNotNull(source.baseUrl), source.allowCleartext)
            probe.list(source, base)
        },
        sourceGateway = roomLibraryRepository,
        credentialStore = credentialStore,
    )
    override val webDavSyncRunner: WebDavSyncRunner = DurableWebDavSyncRunner(
        webDavPersistence,
    ) { source, checkpoint, saveCheckpoint ->
        WebDavSyncEngine(
            client = webDavClient,
            libraryWriteGateway = roomLibraryRepository,
            fingerprintLookup = webDavPersistence::remoteFingerprint,
            metadataEnricher = RangeWebDavMetadataEnricher(
                WebDavRangeReader { rangeSource, entry, start, endInclusive, maxBytes ->
                    webDavClient.readRange(rangeSource, entry.href, start, endInclusive, maxBytes)
                },
                artworkCache = artworkCache,
            ),
            updatePublisher = webDavPersistence::markUpdateAvailable,
        ).sync(source, checkpoint, saveCheckpoint)
    }

    private val downloadRoot = File(appContext.noBackupFilesDir, "offline_downloads")
    private val downloadPersistence = RoomDownloadPersistenceAdapter(database, credentialStore, downloadRoot)
    private val productPreferences = appContext.getSharedPreferences("cleartune_product_settings", Context.MODE_PRIVATE)
    private val downloadScheduler = WorkManagerDownloadScheduler(
        appContext,
        downloadRoot,
        downloadPersistence,
        DownloadNetworkPolicyProvider { productPreferences.getBoolean("wifi_only_downloads", true) },
    )
    private val sourceRemovalCoordinator = RoomWebDavSourceRemovalCoordinator(
        database = database,
        downloadRoot = downloadRoot,
        sourceWork = SourceWorkCancellation(webDavSyncScheduler::cancel),
        downloadWork = object : DownloadWorkCancellation {
            override suspend fun stop(downloadId: DownloadId) = downloadScheduler.stop(downloadId)
            override suspend fun deleteFile(file: File): Boolean = !file.exists() || file.delete()
        },
        credentials = CredentialDeletion(credentialStore::delete),
        retireCheckpoint = webDavPersistence::retireCheckpoint,
        artworkCache = artworkCache,
    )
    val sourceController = SourceController(
        sourceRepository,
        WebDavSourceActionAdapter(
            sourceRepository,
            webDavSourceManager,
            webDavClient,
            webDavSyncScheduler,
            sourceRemovalCoordinator,
            TestedWebDavBrowser { source, draft, relativePath ->
                val temporaryStore = object : CredentialStore {
                    override suspend fun put(alias: com.cleartune.core.model.CredentialAlias, credential: WebDavCredential) = Unit
                    override suspend fun get(alias: com.cleartune.core.model.CredentialAlias) =
                        WebDavCredential(draft.username, draft.password.copyOf())
                    override suspend fun delete(alias: com.cleartune.core.model.CredentialAlias) = Unit
                }
                val temporaryClient = OkHttpWebDavClient(baseHttpClient, temporaryStore)
                val base = WebDavUrlPolicy.normalizeBaseUrl(requireNotNull(source.baseUrl), source.allowCleartext)
                val target = base.newBuilder().apply {
                    relativePath.split('/').filter(String::isNotBlank).forEach(::addPathSegment)
                    if (!build().encodedPath.endsWith('/')) addPathSegment("")
                }.build()
                temporaryClient.list(source, target).map { entry ->
                    com.cleartune.feature.sources.SourceBrowseItem(entry.name, entry.name, entry.isDirectory)
                }
            },
        ),
    )
    val downloadRepository: DownloadRepository = DownloadCoordinator(downloadPersistence, downloadScheduler)
    val downloadCommandsAvailable: Boolean = true
    override val downloadWorkerRunner: DownloadWorkerRunner = ProductionDownloadWorkerRunner(downloadPersistence) { credentials ->
        val client = baseHttpClient.newBuilder().apply {
            val protectionBase = credentials?.protectionBase
            if (credentials != null && protectionBase != null) {
                authenticator(
                    WebDavAuthenticator(
                        baseUrl = protectionBase,
                        credentialProvider = { WebDavCredential(credentials.username, credentials.password) },
                    ),
                )
            }
        }.build()
        DownloadTransfer(client).asExecutor()
    }

    private val localSnapshotAdapter = LocalSnapshotAdapter(roomLibraryRepository, roomLibraryRepository)
    private val localScanCoordinator = LocalScanCoordinator(
        AndroidMediaStoreGateway(appContext.contentResolver),
        localSnapshotAdapter,
    )
    val localScanWorkerRunner = LocalScanWorkerRunner {
        val permission = AudioPermissionPolicy.requiredPermission(Build.VERSION.SDK_INT)
        localScanCoordinator.scan(appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED)
    }
    val workerFactory = LocalScanWorkerFactory(localScanWorkerRunner)
    private val localScanScheduler = LocalScanScheduler(androidx.work.WorkManager.getInstance(appContext))

    private val streamingCacheRoot = File(appContext.cacheDir, "playback_streaming_cache")
    val settingsProductController = AppProductSettingsController(
        appContext,
        scanLibrary = { localScanScheduler.enqueueManualRefresh() },
        cleanUpCache = { clearContainedCache(streamingCacheRoot) },
        cacheRoot = streamingCacheRoot,
        rebuildDownloadConstraints = { wifiOnly ->
            downloadPersistence.observe().first()
                .filter { it.state in setOf(DownloadState.QUEUED, DownloadState.WAITING_FOR_WIFI, DownloadState.RUNNING) }
                .forEach { download ->
                    downloadPersistence.replace(
                        download.copy(
                            state = if (wifiOnly) DownloadState.WAITING_FOR_WIFI else DownloadState.QUEUED,
                        ),
                    )
                    downloadScheduler.enqueue(download.id)
                }
        },
    )
    val playbackRuntimeSettingsProvider = object : PlaybackRuntimeSettingsProvider {
        override fun snapshot(): PlaybackRuntimeSettings = settingsProductController.snapshot().let { settings ->
            PlaybackRuntimeSettings(
                restoreQueue = settings.restoreQueue,
                pauseOnHeadphoneDisconnect = settings.pauseOnHeadphoneDisconnect,
                streamingCacheEnabled = settings.offlineCacheEnabled,
                backgroundPlayback = settings.backgroundPlayback,
                dynamicBackground = settings.dynamicBackground,
                cacheLimitBytes = settings.cacheLimitMb.toLong() * 1024L * 1024L,
            )
        }
        override val updates = settingsProductController.productSettings.map { settings ->
            PlaybackRuntimeSettings(
                restoreQueue = settings.restoreQueue,
                pauseOnHeadphoneDisconnect = settings.pauseOnHeadphoneDisconnect,
                streamingCacheEnabled = settings.offlineCacheEnabled,
                backgroundPlayback = settings.backgroundPlayback,
                dynamicBackground = settings.dynamicBackground,
                cacheLimitBytes = settings.cacheLimitMb.toLong() * 1024L * 1024L,
            )
        }.distinctUntilChanged()
    }

    val librarySessionCatalog: LibrarySessionCatalog = RoomLibrarySessionCatalog(database)
    private val trackTitles = MutableStateFlow<Map<TrackId, String>>(emptyMap())
    val trackTitleFlow = trackTitles
    val downloadTitleResolver = DownloadTitleResolver { trackId -> trackTitles.value[trackId] ?: trackId.value }

    private val mediaBackend = Media3PlaybackBackend(appContext)
    val playbackGateway = PlaybackCoordinator(
        libraryRepository = roomLibraryRepository,
        queueRepository = queueRepository,
        backend = mediaBackend,
        environment = PlaybackEnvironment(
            fileExists = ::fileExists,
            uriReadable = ::uriReadable,
            networkAvailable = ::networkAvailable,
        ),
        historyRecorder = roomLibraryRepository,
    )

    val playbackCredentialResolver = PlaybackCredentialResolver { rawSourceId ->
        runBlocking(Dispatchers.IO) {
            val source = sourceRepository.getSource(SourceId(rawSourceId))
                ?.takeIf { it.enabled && it.type == SourceType.WEBDAV }
                ?: return@runBlocking null
            val baseUrl = WebDavUrlPolicy.normalizeBaseUrl(
                requireNotNull(source.baseUrl),
                source.allowCleartext,
            )
            val credential = source.credentialAlias?.let { credentialStore.get(it) }
                ?: return@runBlocking null
            PlaybackCredentialContext(rawSourceId, baseUrl, credential)
        }
    }

    init {
        applicationScope.launch {
            libraryRepository.observeSongs(SongQuery()).collect { tracks ->
                trackTitles.value = tracks.associate { it.id to it.title }
            }
        }
        applicationScope.launch {
            if (playbackRuntimeSettingsProvider.snapshot().restoreQueue) {
                playbackGateway.syncQueue()
            } else {
                queueRepository.apply(QueueCommand.Replace(emptyList()))
            }
        }
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

    fun scheduleStartupWork() {
        localScanScheduler.enqueueAutomatic()
        applicationScope.launch {
            sourceRemovalCoordinator.reconcile()
            sourceRepository.observeSources().first()
                .filter { it.enabled && it.type == com.cleartune.core.model.SourceType.WEBDAV }
                .forEach { webDavSyncScheduler.enqueue(it.id) }
        }
    }

    fun enqueueLocalScan() = localScanScheduler.enqueueManualRefresh()

    internal fun smokeSnapshot() = AppContainerSmokeSnapshot(
        libraryRepository = libraryRepository::class.java.simpleName,
        sourceRepository = sourceRepository::class.java.simpleName,
        queueRepository = queueRepository::class.java.simpleName,
        workerFactory = workerFactory::class.java.simpleName,
        hasCredentialResolver = true,
        hasRuntimeSettings = true,
    )

    val localScanState get() = localScanCoordinator.state

    fun close() {
        applicationScope.cancel()
        sourceController.close()
        mediaBackend.release()
        database.close()
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

internal data class AppContainerSmokeSnapshot(
    val libraryRepository: String,
    val sourceRepository: String,
    val queueRepository: String,
    val workerFactory: String,
    val hasCredentialResolver: Boolean,
    val hasRuntimeSettings: Boolean,
)
