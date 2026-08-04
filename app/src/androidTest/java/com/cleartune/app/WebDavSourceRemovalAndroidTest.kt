package com.cleartune.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cleartune.core.contracts.CredentialStore
import com.cleartune.core.contracts.WebDavCredential
import com.cleartune.core.database.ClearTuneDatabase
import com.cleartune.core.database.RoomLibraryRepository
import com.cleartune.core.database.entity.DownloadEntity
import com.cleartune.core.database.entity.MusicSourceEntity
import com.cleartune.core.database.entity.PlaybackHistoryEntity
import com.cleartune.core.database.entity.TrackEntity
import com.cleartune.core.database.entity.TrackLocationEntity
import com.cleartune.core.model.CredentialAlias
import com.cleartune.core.model.DownloadId
import com.cleartune.core.model.DownloadState
import com.cleartune.core.model.DownloadSummary
import com.cleartune.core.model.LocationType
import com.cleartune.core.model.PlaylistCommand
import com.cleartune.core.model.QueueCommand
import com.cleartune.core.model.SourceId
import com.cleartune.core.model.TrackId
import com.cleartune.data.webdav.DirectoryListingClient
import com.cleartune.data.webdav.DurableWebDavSyncRunner
import com.cleartune.data.webdav.EnrichedTrackMetadata
import com.cleartune.data.webdav.RemoteFingerprintLookup
import com.cleartune.data.webdav.WebDavEntry
import com.cleartune.data.webdav.WebDavMetadataEnricher
import com.cleartune.data.webdav.WebDavSyncCheckpoint
import com.cleartune.data.webdav.WebDavSyncEngine
import com.cleartune.data.webdav.WebDavWorkerOutcome
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebDavSourceRemovalAndroidTest {
    private lateinit var database: ClearTuneDatabase
    private lateinit var root: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ClearTuneDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        root = File(context.noBackupFilesDir, "source-removal-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun retainingOfflineCopySoftDeletesConfigurationWithoutCascadingProductGraph() = runBlocking {
        val fixture = seedGraph()
        val effects = RecordingRemovalEffects()
        val coordinator = RoomWebDavSourceRemovalCoordinator(database, root, effects, effects, effects)

        coordinator.remove(SOURCE_ID, deleteOfflineCopies = false)

        assertNull(database.sourceDao().source(SOURCE_ID.value))
        assertTrue(requireNotNull(database.sourceDao().tombstone(SOURCE_ID.value)).removed)
        assertFalse(requireNotNull(database.libraryWriteDao().locationIncludingUnavailable(SOURCE_ID.value, "song.flac")).available)
        assertTrue(requireNotNull(database.libraryWriteDao().locationIncludingUnavailable(OFFLINE_SOURCE_ID, "download:$DOWNLOAD_ID")).available)
        assertEquals(fixture.trackId.value, database.playlistDao().items(fixture.playlistId).single().trackId)
        assertEquals(fixture.trackId.value, database.playbackDao().queueItems().single().trackId)
        assertEquals(fixture.trackId.value, database.playbackDao().observeRecentHistory(1).first().single().trackId)
        assertEquals(DownloadState.COMPLETED.name, database.downloadDao().download(DOWNLOAD_ID)?.state)
        assertTrue(fixture.file.isFile)
        assertEquals(listOf(SourceId(SOURCE_ID.value)), effects.canceledSources)
        assertEquals(listOf(DownloadId(DOWNLOAD_ID)), effects.stoppedDownloads)
        assertEquals(listOf(CredentialAlias("webdav-source")), effects.deletedCredentials)
    }

    @Test
    fun deletingOfflineCopyLeavesRecoverableCheckpointUntilFileCleanupCompletes() = runBlocking {
        val fixture = seedGraph()
        val effects = RecordingRemovalEffects(failFileDeletion = true)
        val first = RoomWebDavSourceRemovalCoordinator(database, root, effects, effects, effects)

        first.remove(SOURCE_ID, deleteOfflineCopies = true)

        val pending = requireNotNull(database.downloadDao().download(DOWNLOAD_ID))
        assertEquals(DownloadState.CANCELED.name, pending.state)
        assertTrue(pending.cleanupPending)
        assertTrue(fixture.file.isFile)
        assertFalse(requireNotNull(database.libraryWriteDao().locationIncludingUnavailable(OFFLINE_SOURCE_ID, "download:$DOWNLOAD_ID")).available)

        val recoveredEffects = RecordingRemovalEffects()
        RoomWebDavSourceRemovalCoordinator(database, root, recoveredEffects, recoveredEffects, recoveredEffects)
            .reconcilePendingCleanup()

        val cleaned = requireNotNull(database.downloadDao().download(DOWNLOAD_ID))
        assertFalse(cleaned.cleanupPending)
        assertNull(cleaned.finalPath)
        assertFalse(fixture.file.exists())
    }

    @Test
    fun queuedDownloadIsOwnedBeforeWorkerBeginsAndSourceRemovalStopsIt() = runBlocking {
        database.sourceDao().upsert(
            MusicSourceEntity(
                SOURCE_ID.value, "Remote", "WEBDAV", "https://music.example/dav/", false,
                "webdav-source", true, null,
            ),
        )
        val trackId = TrackId("queued-track")
        database.libraryWriteDao().upsertTrack(TrackEntity(trackId.value, "Queued", 1_000, null, null, 1))
        database.libraryWriteDao().upsertLocation(
            TrackLocationEntity(
                "queued-remote", trackId.value, SOURCE_ID.value, "queued.flac", LocationType.REMOTE_URL.name,
                "https://music.example/dav/queued.flac", true, 4, "v1", "", "queued.flac", 1,
            ),
        )
        val adapter = RoomDownloadPersistenceAdapter(database, NoCredentialStoreForRemoval, root, clock = { 2 })
        adapter.insert(DownloadSummary(DownloadId("queued-download"), trackId, DownloadState.QUEUED))
        val effects = RecordingRemovalEffects()

        RoomWebDavSourceRemovalCoordinator(database, root, effects, effects, effects)
            .remove(SOURCE_ID, deleteOfflineCopies = true)

        val queued = requireNotNull(database.downloadDao().download("queued-download"))
        assertEquals(SOURCE_ID.value, queued.sourceId)
        assertEquals(DownloadState.CANCELED.name, queued.state)
        assertTrue(queued.cleanupPending)
        assertEquals(listOf(DownloadId("queued-download")), effects.stoppedDownloads)
    }

    @Test
    fun removalTransactionClosesConcurrentEnqueueWindow() = runBlocking {
        database.sourceDao().upsert(
            MusicSourceEntity(
                SOURCE_ID.value, "Remote", "WEBDAV", "https://music.example/dav/", false,
                "webdav-source", true, null,
            ),
        )
        val beforeTrack = TrackId("before-removal")
        val afterTrack = TrackId("after-removal")
        listOf(beforeTrack to "before.flac", afterTrack to "after.flac").forEach { (trackId, path) ->
            database.libraryWriteDao().upsertTrack(TrackEntity(trackId.value, path, 1_000, null, null, 1))
            database.libraryWriteDao().upsertLocation(
                TrackLocationEntity(
                    "remote-${trackId.value}", trackId.value, SOURCE_ID.value, path,
                    LocationType.REMOTE_URL.name, "https://music.example/dav/$path", true,
                    4, "v1", "", path, 1,
                ),
            )
        }
        val adapter = RoomDownloadPersistenceAdapter(database, NoCredentialStoreForRemoval, root, clock = { 2 })
        adapter.insert(
            DownloadSummary(DownloadId("before-download"), beforeTrack, DownloadState.QUEUED),
        )
        val effects = BlockingRemovalEffects()
        val coordinator = RoomWebDavSourceRemovalCoordinator(database, root, effects, effects, effects)

        val removal = async { coordinator.remove(SOURCE_ID, deleteOfflineCopies = false) }
        effects.sourceCancellationStarted.await()
        val enqueueFailure = runCatching {
            adapter.insert(
                DownloadSummary(DownloadId("after-download"), afterTrack, DownloadState.QUEUED),
            )
        }.exceptionOrNull()
        effects.releaseSourceCancellation.complete(Unit)
        removal.await()

        assertTrue("enqueue after the removal transaction must fail", enqueueFailure != null)
        assertNull(database.downloadDao().download("after-download"))
        val before = requireNotNull(database.downloadDao().download("before-download"))
        assertEquals(DownloadState.CANCELED.name, before.state)
        assertFalse(before.cleanupPending)
        assertEquals(listOf(DownloadId("before-download")), effects.stoppedDownloads)
    }

    @Test
    fun loadedSyncCannotRepublishRemoteLocationAfterRemovalCommit() = runBlocking {
        database.sourceDao().upsert(
            MusicSourceEntity(
                SOURCE_ID.value, "Remote", "WEBDAV", "https://music.example/dav/", false,
                null, true, null,
            ),
        )
        val trackId = TrackId("late-sync-track")
        database.libraryWriteDao().upsertTrack(TrackEntity(trackId.value, "Before", 1_000, null, null, 1))
        database.libraryWriteDao().upsertLocation(
            TrackLocationEntity(
                "late-sync-location", trackId.value, SOURCE_ID.value, "late.mp3",
                LocationType.REMOTE_URL.name, "https://music.example/dav/late.mp3", true,
                10, "v1", "", "late.mp3", 1,
            ),
        )
        val repository = RoomLibraryRepository(database)
        val checkpoints = object : WebDavCheckpointStore {
            override suspend fun load(sourceId: SourceId): WebDavSyncCheckpoint? = null
            override suspend fun save(checkpoint: WebDavSyncCheckpoint) = Unit
            override suspend fun clear(sourceId: SourceId) = Unit
        }
        val persistence = RoomWebDavPersistenceAdapter(database, repository, checkpoints)
        val syncPausedAfterLoad = CompletableDeferred<Unit>()
        val resumeSync = CompletableDeferred<Unit>()
        val runner = DurableWebDavSyncRunner(persistence) { source, checkpoint, saveCheckpoint ->
            WebDavSyncEngine(
                client = DirectoryListingClient { _, _ ->
                    listOf(
                        WebDavEntry(
                            "https://music.example/dav/late.mp3".toHttpUrl(),
                            "late.mp3",
                            false,
                            11,
                            "v2",
                        ),
                    )
                },
                libraryWriteGateway = repository,
                fingerprintLookup = RemoteFingerprintLookup(persistence::remoteFingerprint),
                metadataEnricher = WebDavMetadataEnricher { _, _ ->
                    syncPausedAfterLoad.complete(Unit)
                    resumeSync.await()
                    EnrichedTrackMetadata("After")
                },
                updatePublisher = persistence::markUpdateAvailable,
            ).sync(source, checkpoint, saveCheckpoint)
        }
        val effects = RecordingRemovalEffects()
        val coordinator = RoomWebDavSourceRemovalCoordinator(
            database,
            root,
            effects,
            effects,
            effects,
            clearCheckpoint = persistence::clearCheckpoint,
        )

        val sync = async { runner.run(SOURCE_ID) }
        syncPausedAfterLoad.await()
        coordinator.remove(SOURCE_ID, deleteOfflineCopies = false)
        assertTrue(requireNotNull(database.sourceDao().tombstone(SOURCE_ID.value)).removed)
        resumeSync.complete(Unit)

        assertEquals(WebDavWorkerOutcome.COMPLETED, sync.await())
        val stable = requireNotNull(
            database.libraryWriteDao().locationIncludingUnavailable(SOURCE_ID.value, "late.mp3"),
        )
        assertEquals("late-sync-location", stable.id)
        assertFalse(stable.available)
    }

    @Test
    fun tombstoneReconciliationRetriesPostCommitDownloadCancellation() = runBlocking {
        database.sourceDao().upsert(
            MusicSourceEntity(
                SOURCE_ID.value, "Remote", "WEBDAV", "https://music.example/dav/", false,
                "webdav-source", true, null,
            ),
        )
        val trackId = TrackId("recoverable-track")
        database.libraryWriteDao().upsertTrack(TrackEntity(trackId.value, "Recoverable", 1_000, null, null, 1))
        database.libraryWriteDao().upsertLocation(
            TrackLocationEntity(
                "recoverable-remote", trackId.value, SOURCE_ID.value, "recoverable.flac",
                LocationType.REMOTE_URL.name, "https://music.example/dav/recoverable.flac", true,
                4, "v1", "", "recoverable.flac", 1,
            ),
        )
        RoomDownloadPersistenceAdapter(database, NoCredentialStoreForRemoval, root, clock = { 2 })
            .insert(DownloadSummary(DownloadId("recoverable-download"), trackId, DownloadState.QUEUED))
        val effects = FailFirstSourceCancellationEffects()
        val coordinator = RoomWebDavSourceRemovalCoordinator(database, root, effects, effects, effects)

        val firstFailure = runCatching {
            coordinator.remove(SOURCE_ID, deleteOfflineCopies = false)
        }.exceptionOrNull()

        assertTrue(firstFailure is IllegalStateException)
        assertTrue(requireNotNull(database.sourceDao().tombstone(SOURCE_ID.value)).removed)
        assertEquals(
            DownloadState.CANCELED.name,
            requireNotNull(database.downloadDao().download("recoverable-download")).state,
        )
        assertTrue(effects.stoppedDownloads.isEmpty())

        val lateLocation = requireNotNull(
            database.libraryWriteDao().locationIncludingUnavailable(SOURCE_ID.value, "recoverable.flac"),
        )
        database.libraryWriteDao().upsertLocation(lateLocation.copy(available = true))
        assertTrue(
            requireNotNull(
                database.libraryWriteDao().locationIncludingUnavailable(SOURCE_ID.value, "recoverable.flac"),
            ).available,
        )

        coordinator.reconcile()

        assertEquals(2, effects.sourceCancellationAttempts)
        assertEquals(listOf(DownloadId("recoverable-download")), effects.stoppedDownloads)
        assertFalse(
            requireNotNull(
                database.libraryWriteDao().locationIncludingUnavailable(SOURCE_ID.value, "recoverable.flac"),
            ).available,
        )
    }

    private suspend fun seedGraph(): Fixture {
        database.sourceDao().upsert(
            MusicSourceEntity(
                SOURCE_ID.value, "Remote", "WEBDAV", "https://music.example/dav/", false,
                "webdav-source", true, null,
            ),
        )
        database.sourceDao().upsert(OfflineDownloadSource.entity())
        val trackId = TrackId("track")
        database.libraryWriteDao().upsertTrack(TrackEntity(trackId.value, "Song", 1_000, null, null, 1))
        database.libraryWriteDao().upsertLocation(
            TrackLocationEntity(
                "remote", trackId.value, SOURCE_ID.value, "song.flac", LocationType.REMOTE_URL.name,
                "https://music.example/dav/song.flac", true, 4, "v1", "", "song.flac", 1,
            ),
        )
        val file = File(root, "song.flac").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        database.libraryWriteDao().upsertLocation(
            TrackLocationEntity(
                "offline", trackId.value, OFFLINE_SOURCE_ID, "download:$DOWNLOAD_ID", LocationType.DOWNLOADED_FILE.name,
                file.toURI().toString(), true, 4, "v1", "", "song.flac", 1,
            ),
        )
        database.downloadDao().upsert(
            DownloadEntity(
                DOWNLOAD_ID, trackId.value, DownloadState.COMPLETED.name, 4, 4, "v1", null,
                file.absolutePath, null, 1, sourceId = SOURCE_ID.value,
            ),
        )
        val queues = com.cleartune.core.database.RoomQueueRepository(database, idFactory = { "queue-item" })
        queues.apply(QueueCommand.Replace(listOf(trackId)))
        val playlists = com.cleartune.core.database.RoomPlaylistRepository(
            database,
            idFactory = ArrayDeque(listOf("playlist", "playlist-item"))::removeFirst,
        )
        playlists.apply(PlaylistCommand.Create("Retained"))
        playlists.apply(PlaylistCommand.AddTrack(com.cleartune.core.model.PlaylistId("playlist"), trackId))
        database.playbackDao().upsertHistory(PlaybackHistoryEntity(trackId = trackId.value, playedAtEpochMs = 1, completed = true))
        return Fixture(trackId, "playlist", file)
    }

    private data class Fixture(val trackId: TrackId, val playlistId: String, val file: File)

    private companion object {
        val SOURCE_ID = SourceId("source")
        const val OFFLINE_SOURCE_ID = "offline-downloads"
        const val DOWNLOAD_ID = "download"
    }
}

private object NoCredentialStoreForRemoval : CredentialStore {
    override suspend fun put(alias: CredentialAlias, credential: WebDavCredential) = Unit
    override suspend fun get(alias: CredentialAlias): WebDavCredential? = null
    override suspend fun delete(alias: CredentialAlias) = Unit
}

private class RecordingRemovalEffects(
    private val failFileDeletion: Boolean = false,
) : SourceWorkCancellation, DownloadWorkCancellation, CredentialDeletion {
    val canceledSources = mutableListOf<SourceId>()
    val stoppedDownloads = mutableListOf<DownloadId>()
    val deletedCredentials = mutableListOf<CredentialAlias>()

    override suspend fun cancel(sourceId: SourceId) { canceledSources += sourceId }
    override suspend fun stop(downloadId: DownloadId) { stoppedDownloads += downloadId }
    override suspend fun delete(alias: CredentialAlias) { deletedCredentials += alias }
    override suspend fun deleteFile(file: File): Boolean = if (failFileDeletion) false else !file.exists() || file.delete()
}

private class BlockingRemovalEffects : SourceWorkCancellation, DownloadWorkCancellation, CredentialDeletion {
    val sourceCancellationStarted = CompletableDeferred<Unit>()
    val releaseSourceCancellation = CompletableDeferred<Unit>()
    val stoppedDownloads = mutableListOf<DownloadId>()

    override suspend fun cancel(sourceId: SourceId) {
        sourceCancellationStarted.complete(Unit)
        releaseSourceCancellation.await()
    }

    override suspend fun stop(downloadId: DownloadId) {
        stoppedDownloads += downloadId
    }

    override suspend fun delete(alias: CredentialAlias) = Unit

    override suspend fun deleteFile(file: File): Boolean = !file.exists() || file.delete()
}

private class FailFirstSourceCancellationEffects :
    SourceWorkCancellation,
    DownloadWorkCancellation,
    CredentialDeletion {
    var sourceCancellationAttempts = 0
    val stoppedDownloads = mutableListOf<DownloadId>()

    override suspend fun cancel(sourceId: SourceId) {
        sourceCancellationAttempts += 1
        if (sourceCancellationAttempts == 1) error("simulated post-commit cancellation failure")
    }

    override suspend fun stop(downloadId: DownloadId) {
        stoppedDownloads += downloadId
    }

    override suspend fun delete(alias: CredentialAlias) = Unit

    override suspend fun deleteFile(file: File): Boolean = !file.exists() || file.delete()
}
