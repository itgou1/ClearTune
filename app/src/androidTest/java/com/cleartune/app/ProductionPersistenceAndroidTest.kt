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
import com.cleartune.core.database.entity.TrackEntity
import com.cleartune.core.database.entity.TrackLocationEntity
import com.cleartune.core.database.model.LibraryIngestRecord
import com.cleartune.core.model.CredentialAlias
import com.cleartune.core.model.DownloadId
import com.cleartune.core.model.DownloadState
import com.cleartune.core.model.LocationType
import com.cleartune.core.model.QueueCommand
import com.cleartune.core.model.QueueItemId
import com.cleartune.core.model.TrackId
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.flow.first

@RunWith(AndroidJUnit4::class)
class ProductionPersistenceAndroidTest {
    private lateinit var database: ClearTuneDatabase
    private lateinit var root: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ClearTuneDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        root = File(context.noBackupFilesDir, "publication-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun completedDownloadPublishesLocationWithRecordAndDeleteMakesItUnavailable() = runBlocking {
        database.libraryWriteDao().upsertSource(
            MusicSourceEntity("source", "Remote", "WEBDAV", "https://music.example/dav/", false, null, true, null),
        )
        database.libraryWriteDao().upsertTrack(TrackEntity("track", "Song", 1_000, null, null, 1))
        database.libraryWriteDao().upsertLocation(
            TrackLocationEntity(
                "remote", "track", "source", "song.flac", LocationType.REMOTE_URL.name,
                "https://music.example/dav/song.flac", true, 4, "etag", "", "song.flac", 1,
            ),
        )
        database.downloadDao().upsert(
            DownloadEntity(
                "download", "track", DownloadState.RUNNING.name, 0, 4, "etag", null, null, null, 1,
            ),
        )
        val adapter = RoomDownloadPersistenceAdapter(database, NoCredentialStore, root, clock = { 2 })
        val finalFile = File(root, "final.flac").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

        adapter.publishDownloadedLocation(DownloadId("download"), generation = 0, bytes = 4, finalPath = finalFile.absolutePath)

        val completed = requireNotNull(database.downloadDao().download("download"))
        val published = database.libraryReadDao().playableLocations("track")
            .single { it.type == LocationType.DOWNLOADED_FILE.name }
        assertEquals(DownloadState.COMPLETED.name, completed.state)
        assertEquals(finalFile.toURI().toString(), published.uri)

        adapter.remove(DownloadId("download"))

        assertFalse(database.libraryReadDao().playableLocations("track").any {
            it.type == LocationType.DOWNLOADED_FILE.name
        })
    }

    @Test
    fun canceledGenerationCannotPublishCompletedLocationAndReconcilesFinalFile() = runBlocking {
        database.libraryWriteDao().upsertSource(
            MusicSourceEntity("source", "Remote", "WEBDAV", "https://music.example/dav/", false, null, true, null),
        )
        database.libraryWriteDao().upsertTrack(TrackEntity("track", "Song", 1_000, null, null, 1))
        database.libraryWriteDao().upsertLocation(
            TrackLocationEntity(
                "remote", "track", "source", "song.flac", LocationType.REMOTE_URL.name,
                "https://music.example/dav/song.flac", true, 4, "etag", "", "song.flac", 1,
            ),
        )
        database.downloadDao().upsert(
            DownloadEntity(
                "download", "track", DownloadState.CANCELED.name, 4, 4, "etag", null, null, null, 1,
                sourceId = "source", workGeneration = 7,
            ),
        )
        val finalFile = File(root, "race.flac").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val adapter = RoomDownloadPersistenceAdapter(database, NoCredentialStore, root, clock = { 2 })

        val published = adapter.publishDownloadedLocation(
            DownloadId("download"),
            generation = 7,
            bytes = 4,
            finalPath = finalFile.absolutePath,
        )

        assertFalse(published)
        assertEquals(DownloadState.CANCELED.name, database.downloadDao().download("download")?.state)
        assertNull(database.libraryWriteDao().locationIncludingUnavailable(OfflineDownloadSource.ID.value, "download:download"))
        assertFalse(finalFile.exists())
    }

    @Test
    fun productionRoomShufflePersistsNonNaturalOccurrenceOrderAndCurrentPosition() = runBlocking {
        database.libraryWriteDao().upsertSource(
            MusicSourceEntity("local", "Local", "LOCAL", null, false, null, true, null),
        )
        (1..4).forEach { index ->
            database.libraryWriteDao().upsertTrack(TrackEntity("track-$index", "Song $index", null, null, null, index.toLong()))
        }
        val ids = ArrayDeque(listOf("q1", "q2", "q3", "q4"))
        val repository = com.cleartune.core.database.RoomQueueRepository(database, idFactory = ids::removeFirst)
        repository.apply(QueueCommand.Replace((1..3).map { TrackId("track-$it") }, startIndex = 1))
        val adapter = RoomPlaybackQueueAdapter(database, repository, clock = { 99 })
        adapter.updatePlaybackState(currentIndex = 1, positionMs = 4_321, shuffleEnabled = true)

        val first = adapter.recoveryState()
        assertEquals(QueueItemId("q2"), first.shuffleOrder.first())
        assertTrue(first.shuffleOrder != listOf(QueueItemId("q1"), QueueItemId("q2"), QueueItemId("q3")))
        assertEquals(1, first.snapshot.currentIndex)
        assertEquals(4_321, first.snapshot.positionMs)

        repository.apply(QueueCommand.AddLast(TrackId("track-4")))
        val recreated = RoomPlaybackQueueAdapter(database, repository).recoveryState()
        assertEquals(first.shuffleOrder, recreated.shuffleOrder.filterNot { it == QueueItemId("q4") })
        assertTrue(QueueItemId("q4") in recreated.shuffleOrder)
    }

    @Test
    fun mediaCatalogUsesCategorySpecificBatchedPages() = runBlocking {
        val source = com.cleartune.core.model.SourceId("local")
        database.libraryWriteDao().applySourceSnapshot(
            source,
            "Local",
            listOf(
                ingest("one", "Alpha", album = "Album", artist = "Artist"),
                ingest("two", "Beta"),
                ingest("three", "Gamma"),
            ),
            1,
        )
        val tracks = com.cleartune.core.database.RoomLibraryRepository(database)
            .observeSongs(com.cleartune.core.model.SongQuery()).first()
        val playlist = com.cleartune.core.database.RoomPlaylistRepository(database, idFactory = { "p" })
        playlist.apply(com.cleartune.core.model.PlaylistCommand.Create("List"))
        playlist.apply(com.cleartune.core.model.PlaylistCommand.AddTrack(com.cleartune.core.model.PlaylistId("p"), tracks.single { it.title == "Gamma" }.id))
        val catalog = RoomLibrarySessionCatalog(database)

        assertEquals(listOf("Alpha", "Beta", "Gamma"), catalog.childrenPage("songs", 0, 10).map { it.title })
        assertEquals(listOf("Alpha"), catalog.childrenPage("albums", 0, 10).map { it.title })
        assertEquals(listOf("Alpha"), catalog.childrenPage("artists", 0, 10).map { it.title })
        assertEquals(listOf("Gamma"), catalog.childrenPage("playlists", 0, 10).map { it.title })
        assertEquals(listOf("Beta"), catalog.childrenPage("songs", 1, 1).map { it.title })
    }

    private fun ingest(key: String, title: String, album: String? = null, artist: String? = null) =
        LibraryIngestRecord(
            sourceKey = key,
            uri = "content://audio/$key",
            displayName = "$key.mp3",
            relativeFolder = "Music",
            title = title,
            albumTitle = album,
            artistNames = listOfNotNull(artist),
            durationMs = 1_000,
            artworkRef = null,
            sizeBytes = 10,
            modifiedEpochSeconds = 1,
            addedAtEpochMs = 1,
        )
}

private object NoCredentialStore : CredentialStore {
    override suspend fun put(alias: CredentialAlias, credential: WebDavCredential) = Unit
    override suspend fun get(alias: CredentialAlias): WebDavCredential? = null
    override suspend fun delete(alias: CredentialAlias) = Unit
}
