package com.cleartune.app

import androidx.work.Configuration
import com.cleartune.core.model.MusicSource
import com.cleartune.core.model.SourceId
import com.cleartune.core.model.SourceType
import com.cleartune.data.download.DownloadWorkerHost
import com.cleartune.data.local.LocalAudioSnapshot
import com.cleartune.data.local.LocalScanPhase
import com.cleartune.data.local.LocalScanProgress
import com.cleartune.data.local.LocalScanState
import com.cleartune.data.local.LocalSnapshotRequest
import com.cleartune.data.webdav.WebDavSyncWorkerHost
import com.cleartune.playback.LibrarySessionCatalogOwner
import com.cleartune.core.database.model.FolderRow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppContainerIntegrationTest {
    @Test
    fun `credential matching uses exact parsed origin and path segments`() {
        val root = webDav("root", "https://music.example:443/dav/root/")
        val nested = webDav("nested", "https://music.example/dav/root/private/")
        val cleartext = webDav("http", "http://clear.example:80/dav/", allowCleartext = true)
        val sources = listOf(root, nested, cleartext)

        assertSame(nested, SourceOriginMatcher.match(sources, "https://MUSIC.example/dav/root/private/song.flac"))
        assertSame(root, SourceOriginMatcher.match(sources, "https://music.example:443/dav/root/song.flac"))
        assertSame(cleartext, SourceOriginMatcher.match(sources, "http://clear.example/dav/song.flac"))
        assertNull(SourceOriginMatcher.match(sources, "https://music.example/dav/rooted/song.flac"))
        assertNull(SourceOriginMatcher.match(sources, "https://music.example.evil/dav/root/song.flac"))
        assertNull(SourceOriginMatcher.match(sources, "http://music.example/dav/root/song.flac"))
        assertNull(SourceOriginMatcher.match(sources, "https://user@music.example/dav/root/song.flac"))
        assertNull(SourceOriginMatcher.match(sources, "https://music.example/dav/root/../secret.flac"))
    }

    @Test
    fun `application provisions every process recreated worker and media catalog host`() {
        assertTrue(DownloadWorkerHost::class.java.isAssignableFrom(ClearTuneApplication::class.java))
        assertTrue(WebDavSyncWorkerHost::class.java.isAssignableFrom(ClearTuneApplication::class.java))
        assertTrue(LibrarySessionCatalogOwner::class.java.isAssignableFrom(ClearTuneApplication::class.java))
        assertTrue(Configuration.Provider::class.java.isAssignableFrom(ClearTuneApplication::class.java))
    }

    @Test
    fun `local snapshot adapter converts every row and reports bounded committed progress`() = runBlocking {
        val store = RecordingLocalStore()
        val adapter = LocalSnapshotAdapter(store, store)
        val records = (1..5).map { index ->
            LocalAudioSnapshot(
                sourceKey = "mediastore:$index",
                contentUri = "content://media/audio/$index",
                displayName = "song-$index.flac",
                relativeFolder = "Music/Live",
                title = "Song $index",
                album = "Album",
                artistNames = listOf("Artist"),
                durationMs = index * 1_000L,
                sizeBytes = index * 100L,
                modifiedEpochSeconds = index.toLong(),
            )
        }

        val result = adapter.applySnapshot(
            LocalSnapshotRequest(
                sourceId = SourceId("local"),
                sourceName = "Local music",
                records = records,
                syncedAtEpochMs = 99,
                warningCount = 1,
                retainedSourceKeys = records.mapTo(linkedSetOf(), LocalAudioSnapshot::sourceKey),
                batchSize = 2,
            ),
            store.progress::add,
        )

        assertEquals(5, result.inserted)
        assertEquals(listOf(2, 4, 5), store.progress)
        assertEquals(records.map(LocalAudioSnapshot::sourceKey), store.records.map { it.sourceKey })
        assertEquals("content://media/audio/1", store.records.first().uri)

        adapter.reportProgress(
            LocalScanProgress(
                sessionId = "local:99",
                sourceId = SourceId("local"),
                startedAtEpochMs = 99,
                completedAtEpochMs = 100,
                state = LocalScanState(LocalScanPhase.COMPLETED, processed = 5, total = 5, warningCount = 1),
            ),
        )
        assertEquals("COMPLETED", store.phase)
        assertEquals(5, store.persistedProcessed)
    }

    @Test
    fun `production binding contract contains no empty in memory or feature persistence adapters`() {
        val bindings = ProductionBindingContract.bindings

        assertEquals(
            setOf(
                "RoomLibraryRepository",
                "RoomPlaylistRepository",
                "RoomQueueRepository",
                "RoomSettingsRepository",
                "EncryptedCredentialStore",
                "LocalSnapshotAdapter",
                "RoomWebDavPersistenceAdapter",
                "RoomDownloadPersistenceAdapter",
                "WebDavSourceActionAdapter",
                "RoomLibrarySessionCatalog",
            ),
            bindings.mapTo(linkedSetOf()) { it.simpleName },
        )
        assertFalse(bindings.any { it.simpleName.startsWith("Empty") })
        assertFalse(bindings.any { it.simpleName.contains("InMemory") })
        assertFalse(bindings.any { it.name == "com.cleartune.feature.settings.PersistentSettingsRepository" })
    }

    @Test
    fun `folder projection preserves its authoritative source label`() {
        assertEquals(
            "Home NAS",
            FolderRow("Artist/Album", 2, "Home NAS").toLibraryFolderUi().sourceName,
        )
    }

    private fun webDav(id: String, url: String, allowCleartext: Boolean = false) = MusicSource(
        id = SourceId(id),
        name = id,
        type = SourceType.WEBDAV,
        baseUrl = url,
        allowCleartext = allowCleartext,
    )
}

private class RecordingLocalStore :
    com.cleartune.core.database.LibrarySnapshotStore,
    com.cleartune.core.database.SyncSessionStore {
    val records = mutableListOf<com.cleartune.core.database.model.LibraryIngestRecord>()
    val progress = mutableListOf<Int>()
    var phase: String? = null
    var persistedProcessed: Int? = null

    override suspend fun applyLocalSnapshot(
        sourceId: SourceId,
        sourceName: String,
        records: List<com.cleartune.core.database.model.LibraryIngestRecord>,
        syncedAtEpochMs: Long,
        warningCount: Int,
        retainedSourceKeys: Set<String>,
    ): com.cleartune.core.model.MutationResult {
        this.records += records
        return com.cleartune.core.model.MutationResult(inserted = records.size)
    }

    override suspend fun recordSyncSession(
        sessionId: String,
        sourceId: SourceId,
        startedAtEpochMs: Long,
        completedAtEpochMs: Long?,
        phase: String,
        processed: Int,
        total: Int,
        warningCount: Int,
        errorMessage: String?,
    ) {
        this.phase = phase
        persistedProcessed = processed
    }
}
