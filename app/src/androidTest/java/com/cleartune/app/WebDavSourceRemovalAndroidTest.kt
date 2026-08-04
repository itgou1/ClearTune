package com.cleartune.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cleartune.core.contracts.CredentialStore
import com.cleartune.core.contracts.WebDavCredential
import com.cleartune.core.database.ClearTuneDatabase
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
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
