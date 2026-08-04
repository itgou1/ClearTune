package com.cleartune.app

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Base64
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
import com.cleartune.core.database.RoomPlaylistRepository
import com.cleartune.core.database.RoomQueueRepository
import com.cleartune.core.database.RoomSettingsRepository
import com.cleartune.core.model.DownloadState
import com.cleartune.core.model.SongQuery
import com.cleartune.core.model.TrackId
import com.cleartune.core.network.WebDavAuthenticator
import com.cleartune.core.network.WebDavUrlPolicy
import com.cleartune.data.download.DownloadCoordinator
import com.cleartune.data.download.DownloadTransfer
import com.cleartune.data.download.DownloadWorkerHost
import com.cleartune.data.download.DownloadWorkerRunner
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
import com.cleartune.data.webdav.EncryptedCredentialStore
import com.cleartune.data.webdav.OkHttpWebDavClient
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
import com.cleartune.playback.PlaybackEnvironment
import com.cleartune.playback.PlaybackRequestHeadersProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient

class AppContainer(context: Context) : DownloadWorkerHost, WebDavSyncWorkerHost {
    private val appContext = context.applicationContext
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val database = ClearTuneDatabase.build(appContext)
    private val roomLibraryRepository = RoomLibraryRepository(database)
    private val roomPlaylistRepository = RoomPlaylistRepository(database)
    private val roomQueueRepository = RoomQueueRepository(database)
    private val baseHttpClient = OkHttpClient.Builder().build()

    val libraryRepository: LibraryRepository = roomLibraryRepository
    val sourceRepository: SourceRepository = roomLibraryRepository
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
    val sourceController = SourceController(
        sourceRepository,
        WebDavSourceActionAdapter(sourceRepository, webDavSourceManager, webDavClient, webDavSyncScheduler),
    )

    override val webDavSyncRunner: WebDavSyncRunner = DurableWebDavSyncRunner(
        webDavPersistence,
    ) { source, checkpoint, saveCheckpoint ->
        WebDavSyncEngine(
            client = webDavClient,
            libraryWriteGateway = roomLibraryRepository,
            fingerprintLookup = webDavPersistence::remoteFingerprint,
            updatePublisher = webDavPersistence::markUpdateAvailable,
        ).sync(source, checkpoint, saveCheckpoint)
    }

    private val downloadRoot = File(appContext.noBackupFilesDir, "offline_downloads")
    private val downloadPersistence = RoomDownloadPersistenceAdapter(database, credentialStore, downloadRoot)
    private val downloadScheduler = WorkManagerDownloadScheduler(appContext, downloadRoot, downloadPersistence)
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

    val settingsProductController: SettingsProductController = AppProductSettingsController(
        appContext,
        scanLibrary = { localScanScheduler.enqueueManualRefresh() },
        cleanUpCache = { clearContainedCache(appContext.cacheDir) },
    )

    val librarySessionCatalog: LibrarySessionCatalog = RoomLibrarySessionCatalog(roomLibraryRepository)
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
    )

    val playbackRequestHeadersProvider = PlaybackRequestHeadersProvider { rawUri ->
        runBlocking(Dispatchers.IO) {
            val source = SourceOriginMatcher.match(sourceRepository.observeSources().first(), rawUri.toString())
            val credential = source?.credentialAlias?.let { credentialStore.get(it) }
            credential?.let(::basicAuthorizationHeader).orEmpty()
        }
    }

    init {
        applicationScope.launch {
            libraryRepository.observeSongs(SongQuery()).collect { tracks ->
                trackTitles.value = tracks.associate { it.id to it.title }
            }
        }
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

    fun scheduleStartupWork() {
        localScanScheduler.enqueueAutomatic()
        applicationScope.launch {
            sourceRepository.observeSources().first()
                .filter { it.enabled && it.type == com.cleartune.core.model.SourceType.WEBDAV }
                .forEach { webDavSyncScheduler.enqueue(it.id) }
        }
    }

    fun enqueueLocalScan() = localScanScheduler.enqueueManualRefresh()

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

private fun basicAuthorizationHeader(credential: WebDavCredential): Map<String, String> {
    val username = credential.username.toByteArray(Charsets.UTF_8)
    val passwordCopy = credential.password.copyOf()
    var encodedPassword: ByteBuffer? = null
    var password = ByteArray(0)
    var combined = ByteArray(0)
    return try {
        encodedPassword = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(passwordCopy))
        password = ByteArray(requireNotNull(encodedPassword).remaining()).also(requireNotNull(encodedPassword)::get)
        combined = ByteArrayOutputStream(username.size + password.size + 1).use { output ->
            output.write(username)
            output.write(':'.code)
            output.write(password)
            output.toByteArray()
        }
        mapOf("Authorization" to "Basic ${Base64.encodeToString(combined, Base64.NO_WRAP)}")
    } finally {
        username.fill(0)
        passwordCopy.fill('\u0000')
        password.fill(0)
        combined.fill(0)
        encodedPassword?.takeIf(ByteBuffer::hasArray)?.array()?.fill(0)
        credential.password.fill('\u0000')
    }
}
