package com.cleartune.app

import android.content.Context
import androidx.room.withTransaction
import com.cleartune.core.contracts.LibraryRepository
import com.cleartune.core.contracts.PlaylistRepository
import com.cleartune.core.contracts.QueueRepository
import com.cleartune.core.database.ClearTuneDatabase
import com.cleartune.core.database.LibraryBrowseStore
import com.cleartune.core.database.RoomLibraryRepository
import com.cleartune.core.database.RoomPlaylistRepository
import com.cleartune.core.database.RoomQueueRepository
import com.cleartune.core.database.RoomSettingsRepository
import com.cleartune.core.database.dao.PlaybackDao
import com.cleartune.core.database.entity.PlaybackQueueEntity
import com.cleartune.core.database.entity.PlaybackStateEntity
import com.cleartune.core.database.model.FolderRow
import com.cleartune.core.database.model.toDomain
import com.cleartune.core.model.Album
import com.cleartune.core.model.Artist
import com.cleartune.core.model.ArtistId
import com.cleartune.core.model.PlaylistId
import com.cleartune.core.model.QueueCommand
import com.cleartune.core.model.QueueItem
import com.cleartune.core.model.QueueItemId
import com.cleartune.core.model.QueueSnapshot
import com.cleartune.core.model.RepeatMode
import com.cleartune.core.model.SettingsCommand
import com.cleartune.core.model.TrackId
import com.cleartune.core.model.TrackSummary
import com.cleartune.core.model.SourceId
import com.cleartune.core.model.SourceType
import com.cleartune.data.webdav.EncryptedCredentialStore
import com.cleartune.feature.library.LibraryBrowsePort
import com.cleartune.feature.library.LibraryFolderUi
import com.cleartune.feature.playlists.PlaylistDetails
import com.cleartune.feature.playlists.PlaylistDetailsProvider
import com.cleartune.feature.playlists.PlaylistItemRecord
import com.cleartune.feature.settings.SettingsOperationState
import com.cleartune.feature.settings.SettingsProductCommand
import com.cleartune.feature.settings.SettingsProductController
import com.cleartune.feature.settings.SettingsProductState
import com.cleartune.playback.LibraryCatalogTrack
import com.cleartune.playback.LibrarySessionCatalog
import com.cleartune.playback.PlaybackQueueRecoveryProvider
import com.cleartune.playback.PlaybackQueueStateWriter
import com.cleartune.playback.QueueRecoveryState
import java.io.File
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RoomLibraryBrowseAdapter(
    private val repository: LibraryRepository,
    private val store: LibraryBrowseStore,
) : LibraryBrowsePort {
    override fun observeAlbums(): Flow<List<Album>> = store.observeAlbums().map { rows -> rows.map { it.toDomain() } }
    override fun observeArtists(): Flow<List<Artist>> = store.observeArtists().map { rows -> rows.map { it.toDomain() } }
    override fun observeFolders(): Flow<List<LibraryFolderUi>> = store.observeFolders().map { rows ->
        rows.map(FolderRow::toLibraryFolderUi)
    }

    override fun observeArtistTracks(artistId: ArtistId): Flow<List<TrackSummary>> = combine(
        store.observeArtistTracks(artistId),
        observeArtists(),
    ) { tracks, artists -> tracks.takeIf { artists.any { it.id == artistId } }.orEmpty() }

    override fun observeArtistAlbums(artistId: ArtistId): Flow<List<Album>> = combine(
        store.observeArtistAlbums(artistId).map { rows -> rows.map { it.toDomain() } },
        observeArtists(),
    ) { albums, artists -> albums.takeIf { artists.any { it.id == artistId } }.orEmpty() }

    override fun observeFolderTracks(path: String): Flow<List<TrackSummary>> = store.observeFolderTracks(path)
    override fun observeFolderTracks(folder: LibraryFolderUi): Flow<List<TrackSummary>> =
        store.observeFolderTracks(folder.sourceId, folder.path)
}

internal fun FolderRow.toLibraryFolderUi(): LibraryFolderUi =
    LibraryFolderUi(
        path = relativeFolder,
        trackCount = trackCount,
        sourceName = sourceName,
        sourceId = sourceId?.let(::SourceId),
        sourceType = sourceType?.let { runCatching { SourceType.valueOf(it) }.getOrNull() },
    )

class RoomPlaylistDetailsAdapter(
    private val database: ClearTuneDatabase,
    private val playlists: PlaylistRepository,
) : PlaylistDetailsProvider {
    override fun observePlaylist(playlistId: PlaylistId): Flow<PlaylistDetails?> = combine(
        playlists.observePlaylists(),
        database.playlistDao().observeItems(playlistId.value),
    ) { summaries, items ->
        summaries.firstOrNull { it.id == playlistId }?.let { summary ->
            PlaylistDetails(
                id = playlistId,
                name = summary.name,
                items = items.map { PlaylistItemRecord(com.cleartune.core.model.PlaylistItemId(it.id), TrackId(it.trackId)) },
            )
        }
    }
}

class RoomPlaybackQueueAdapter(
    private val database: ClearTuneDatabase,
    private val repository: RoomQueueRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : QueueRepository, PlaybackQueueStateWriter, PlaybackQueueRecoveryProvider {
    override fun observeQueue(): Flow<QueueSnapshot> = repository.observeQueue()
    override suspend fun apply(command: QueueCommand) = repository.apply(command)

    override suspend fun updatePlaybackState(
        currentIndex: Int?,
        positionMs: Long?,
        playWhenReady: Boolean?,
        repeatMode: RepeatMode?,
        shuffleEnabled: Boolean?,
    ) {
        database.withTransaction {
            val dao = database.playbackDao()
            val items = dao.queueItems()
            val existing = dao.playbackState() ?: defaultState()
            val nextCurrentIndex = currentIndex?.let { index ->
                if (items.isEmpty()) -1 else index.coerceIn(items.indices)
            } ?: existing.currentIndex
            val enablingShuffle = shuffleEnabled == true && !existing.shuffleEnabled
            val nextShuffleOrder = if (enablingShuffle) {
                shuffledOccurrenceOrder(
                    items.map { it.id },
                    items.getOrNull(nextCurrentIndex)?.id,
                )
            } else {
                existing.shuffleOrder
            }
            dao.upsertQueue(PlaybackQueueEntity(PlaybackDao.DEFAULT_QUEUE_ID, clock()))
            dao.upsertPlaybackState(
                existing.copy(
                    queueId = PlaybackDao.DEFAULT_QUEUE_ID,
                    currentIndex = nextCurrentIndex,
                    positionMs = positionMs?.coerceAtLeast(0) ?: existing.positionMs,
                    playWhenReady = playWhenReady ?: existing.playWhenReady,
                    repeatMode = repeatMode?.name ?: existing.repeatMode,
                    shuffleEnabled = shuffleEnabled ?: existing.shuffleEnabled,
                    shuffleOrder = nextShuffleOrder,
                ),
            )
        }
    }

    override suspend fun recoveryState(): QueueRecoveryState = database.withTransaction {
        val dao = database.playbackDao()
        val state = dao.playbackState()
        val items = dao.queueItems().map { QueueItem(QueueItemId(it.id), TrackId(it.trackId)) }
        val snapshot = QueueSnapshot(
            items = items,
            currentIndex = state?.currentIndex?.let { if (items.isEmpty()) -1 else it.coerceIn(items.indices) } ?: -1,
            positionMs = state?.positionMs?.coerceAtLeast(0) ?: 0,
            playWhenReady = state?.playWhenReady ?: false,
            repeatMode = state?.repeatMode?.let { runCatching { RepeatMode.valueOf(it) }.getOrNull() } ?: RepeatMode.OFF,
            shuffleEnabled = state?.shuffleEnabled ?: false,
        )
        val order = state?.shuffleOrder.orEmpty().split(SHUFFLE_SEPARATOR)
            .filter(String::isNotBlank)
            .map(::QueueItemId)
        QueueRecoveryState(snapshot, order)
    }

    private fun defaultState() = PlaybackStateEntity(
        queueId = PlaybackDao.DEFAULT_QUEUE_ID,
        currentIndex = -1,
        positionMs = 0,
        playWhenReady = false,
        repeatMode = RepeatMode.OFF.name,
        shuffleEnabled = false,
        shuffleOrder = "",
    )

    private companion object { const val SHUFFLE_SEPARATOR = "\u001f" }
}

private fun shuffledOccurrenceOrder(queueOrder: List<String>, currentId: String?): String {
    if (queueOrder.isEmpty()) return ""
    val remaining = queueOrder.filterNot { it == currentId }.toMutableList()
    java.util.Collections.shuffle(remaining, SecureRandom())
    if (remaining.size > 1 && listOfNotNull(currentId) + remaining == queueOrder) {
        remaining.reverse()
    }
    return (listOfNotNull(currentId) + remaining).joinToString("\u001f")
}

class RoomLibrarySessionCatalog(
    private val database: ClearTuneDatabase,
) : LibrarySessionCatalog {
    override fun children(parentId: String): List<LibraryCatalogTrack> =
        childrenPage(parentId, page = 0, pageSize = Int.MAX_VALUE)

    override fun childrenPage(parentId: String, page: Int, pageSize: Int): List<LibraryCatalogTrack> =
        runBlocking(Dispatchers.IO) {
            val safeSize = pageSize.coerceIn(1, MAX_PAGE_SIZE)
            val offset = (page.coerceAtLeast(0).toLong() * safeSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val dao = database.libraryReadDao()
            when (parentId) {
                "songs", "downloads" -> dao.mediaCatalogPage(parentId, safeSize, offset).map { it.toCatalogTrack() }
                "albums" -> dao.mediaCatalogAlbumNodes(safeSize, offset).map { it.toCatalogNode() }
                "artists" -> dao.mediaCatalogArtistNodes(safeSize, offset).map { it.toCatalogNode() }
                "playlists" -> dao.mediaCatalogPlaylistNodes(safeSize, offset).map { it.toCatalogNode() }
                else -> parentId.catalogEntity()?.let { (type, id) ->
                    dao.mediaCatalogEntityPage(type, id, safeSize, offset).map { it.toCatalogTrack() }
                }.orEmpty()
            }
        }

    override fun resolve(mediaId: String): LibraryCatalogTrack? = runBlocking(Dispatchers.IO) {
        database.libraryReadDao().mediaCatalogItem(mediaId)?.toCatalogTrack()
    }

    private fun com.cleartune.core.database.model.MediaCatalogRow.toCatalogTrack() = LibraryCatalogTrack(
        mediaId = mediaId,
        title = title,
        artist = artistNames?.split('\u001f')?.joinToString()?.takeIf(String::isNotBlank),
        album = albumTitle,
        artworkUri = artworkUri,
        playbackUri = playbackUri,
        mimeType = playbackUri.catalogMimeType(),
        sourceId = sourceId,
        locationId = locationId,
    )

    private fun com.cleartune.core.database.model.MediaCatalogNodeRow.toCatalogNode() = LibraryCatalogTrack(
        mediaId = mediaId,
        title = title,
        artworkUri = artworkUri,
        browsable = true,
        playable = false,
    )

    private fun String.catalogEntity(): Pair<String, String>? {
        val type = substringBefore(':')
        val id = substringAfter(':', missingDelimiterValue = "")
        return if (type in ENTITY_TYPES && id.isNotBlank()) type to id else null
    }

    private fun String.catalogMimeType(): String? = when (substringBefore('?').substringAfterLast('.').lowercase()) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> null
    }

    private companion object {
        val ENTITY_TYPES = setOf("album", "artist", "playlist")
        const val MAX_PAGE_SIZE = 500
    }
}

class AppProductSettingsController private constructor(
    private val preferences: ProductPreferenceStore,
    private val scanLibrary: suspend () -> Unit,
    private val cleanUpCache: suspend () -> Unit,
    private val cacheRoot: File,
    private val rebuildDownloadConstraints: suspend (Boolean) -> Unit,
) : SettingsProductController {
    constructor(
        context: Context,
        scanLibrary: suspend () -> Unit,
        cleanUpCache: suspend () -> Unit,
        cacheRoot: File = context.cacheDir,
        rebuildDownloadConstraints: suspend (Boolean) -> Unit = {},
    ) : this(
        SharedProductPreferenceStore(
            context.getSharedPreferences("cleartune_product_settings", Context.MODE_PRIVATE),
        ),
        scanLibrary,
        cleanUpCache,
        cacheRoot,
        rebuildDownloadConstraints,
    )

    internal constructor(
        cacheRoot: File,
        scanLibrary: suspend () -> Unit,
        cleanUpCache: suspend () -> Unit,
        rebuildDownloadConstraints: suspend (Boolean) -> Unit = {},
    ) : this(InMemoryProductPreferenceStore(), scanLibrary, cleanUpCache, cacheRoot, rebuildDownloadConstraints)

    private val mutex = Mutex()
    private val cacheUsage = CacheUsageMonitor(cacheRoot)
    private val state = MutableStateFlow(
        SettingsProductState(
            restoreQueue = preferences.boolean("restore_queue", true),
            pauseOnHeadphoneDisconnect = preferences.boolean("headphone_pause", true),
            offlineCacheEnabled = preferences.boolean("offline_cache", true),
            wifiOnlyDownloads = preferences.boolean("wifi_only_downloads", true),
            backgroundPlayback = preferences.boolean("background_playback", false),
            dynamicBackground = preferences.boolean("dynamic_background", true),
            cacheLimitMb = preferences.integer("cache_limit_mb", 512).coerceIn(64, 8_192),
            cachedBytes = cacheUsage.bytes.value,
            scanLibrary = SettingsOperationState.Ready,
            cleanUpCache = SettingsOperationState.Ready,
            openLicenses = SettingsOperationState.Unavailable("License information is not bundled in this scope"),
        ),
    )
    override val productSettings: Flow<SettingsProductState> = combine(state, cacheUsage.bytes) { value, bytes ->
        value.copy(cachedBytes = bytes)
    }

    fun snapshot(): SettingsProductState {
        cacheUsage.refresh()
        return state.value.copy(cachedBytes = cacheUsage.bytes.value)
    }

    override suspend fun dispatch(command: SettingsProductCommand) = mutex.withLock {
        state.value = when (command) {
            is SettingsProductCommand.SetRestoreQueue -> state.value.copy(restoreQueue = command.enabled)
                .persist("restore_queue", command.enabled)
            is SettingsProductCommand.SetPauseOnHeadphoneDisconnect -> state.value
                .copy(pauseOnHeadphoneDisconnect = command.enabled).persist("headphone_pause", command.enabled)
            is SettingsProductCommand.SetOfflineCacheEnabled -> state.value.copy(offlineCacheEnabled = command.enabled)
                .persist("offline_cache", command.enabled)
            is SettingsProductCommand.SetWifiOnlyDownloads -> {
                val next = state.value.copy(wifiOnlyDownloads = command.enabled)
                    .persist("wifi_only_downloads", command.enabled)
                state.value = next
                rebuildDownloadConstraints(command.enabled)
                next
            }
            is SettingsProductCommand.SetBackgroundPlayback -> state.value.copy(backgroundPlayback = command.enabled)
                .persist("background_playback", command.enabled)
            is SettingsProductCommand.SetDynamicBackground -> state.value.copy(dynamicBackground = command.enabled)
                .persist("dynamic_background", command.enabled)
            is SettingsProductCommand.SetCacheLimitMb -> state.value.copy(cacheLimitMb = command.megabytes.coerceIn(64, 8_192))
                .also { preferences.putInt("cache_limit_mb", it.cacheLimitMb) }
            SettingsProductCommand.RefreshCacheUsage -> state.value.also { cacheUsage.refresh() }
            SettingsProductCommand.ScanLibrary -> runOperation("scan", scanLibrary)
            SettingsProductCommand.CleanUpCache -> runOperation("cleanup", cleanUpCache)
            SettingsProductCommand.OpenLicenses -> error("License information is not bundled in this scope")
        }
    }

    private suspend fun runOperation(kind: String, action: suspend () -> Unit): SettingsProductState {
        state.value = if (kind == "scan") state.value.copy(scanLibrary = SettingsOperationState.Running)
        else state.value.copy(cleanUpCache = SettingsOperationState.Running)
        return try {
            action()
            cacheUsage.refresh()
            if (kind == "scan") state.value.copy(scanLibrary = SettingsOperationState.Success("Completed"))
            else state.value.copy(
                cleanUpCache = SettingsOperationState.Success("Completed"),
                cachedBytes = cacheBytes(cacheRoot),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val error = SettingsOperationState.Error(failure.message ?: "Operation failed")
            if (kind == "scan") state.value.copy(scanLibrary = error) else state.value.copy(cleanUpCache = error)
        }
    }

    private fun SettingsProductState.persist(key: String, value: Boolean): SettingsProductState =
        also { preferences.putBoolean(key, value) }
}

private interface ProductPreferenceStore {
    fun boolean(key: String, default: Boolean): Boolean
    fun integer(key: String, default: Int): Int
    fun putBoolean(key: String, value: Boolean)
    fun putInt(key: String, value: Int)
}

private class SharedProductPreferenceStore(
    private val preferences: android.content.SharedPreferences,
) : ProductPreferenceStore {
    override fun boolean(key: String, default: Boolean): Boolean = preferences.getBoolean(key, default)
    override fun integer(key: String, default: Int): Int = preferences.getInt(key, default)
    override fun putBoolean(key: String, value: Boolean) { preferences.edit().putBoolean(key, value).apply() }
    override fun putInt(key: String, value: Int) { preferences.edit().putInt(key, value).apply() }
}

private class InMemoryProductPreferenceStore : ProductPreferenceStore {
    private val values = mutableMapOf<String, Any>()
    override fun boolean(key: String, default: Boolean): Boolean = values[key] as? Boolean ?: default
    override fun integer(key: String, default: Int): Int = values[key] as? Int ?: default
    override fun putBoolean(key: String, value: Boolean) { values[key] = value }
    override fun putInt(key: String, value: Int) { values[key] = value }
}

object ProductionBindingContract {
    val bindings: List<Class<*>> = listOf(
        RoomLibraryRepository::class.java,
        RoomPlaylistRepository::class.java,
        RoomQueueRepository::class.java,
        RoomSettingsRepository::class.java,
        EncryptedCredentialStore::class.java,
        LocalSnapshotAdapter::class.java,
        RoomWebDavPersistenceAdapter::class.java,
        RoomDownloadPersistenceAdapter::class.java,
        WebDavSourceActionAdapter::class.java,
        RoomLibrarySessionCatalog::class.java,
    )
}

internal fun clearContainedCache(cacheRoot: File) {
    val root = cacheRoot.canonicalFile
    root.walkBottomUp().filter { it != root }.forEach { candidate ->
        require(candidate.canonicalFile.toPath().startsWith(root.toPath()))
        if (candidate.exists() && !candidate.delete()) error("Unable to clear cache")
    }
}

internal fun cacheBytes(cacheRoot: File): Long = cacheRoot
    .walkTopDown()
    .filter(File::isFile)
    .sumOf(File::length)

internal class CacheUsageMonitor(private val cacheRoot: File) {
    private val mutableBytes = MutableStateFlow(cacheBytes(cacheRoot))
    val bytes: StateFlow<Long> = mutableBytes.asStateFlow()

    fun refresh() {
        mutableBytes.value = cacheBytes(cacheRoot)
    }
}
