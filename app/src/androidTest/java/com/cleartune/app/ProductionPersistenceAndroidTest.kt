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
import com.cleartune.core.model.CredentialAlias
import com.cleartune.core.model.DownloadId
import com.cleartune.core.model.DownloadState
import com.cleartune.core.model.LocationType
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

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

        adapter.publishDownloadedLocation(DownloadId("download"), 4, finalFile.absolutePath)

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
}

private object NoCredentialStore : CredentialStore {
    override suspend fun put(alias: CredentialAlias, credential: WebDavCredential) = Unit
    override suspend fun get(alias: CredentialAlias): WebDavCredential? = null
    override suspend fun delete(alias: CredentialAlias) = Unit
}
