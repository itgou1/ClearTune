package com.cleartune.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cleartune.core.database.model.LibraryIngestRecord
import com.cleartune.core.database.entity.DownloadEntity
import com.cleartune.core.database.entity.PlaybackHistoryEntity
import com.cleartune.core.model.SourceId
import com.cleartune.core.model.LibraryMutation
import com.cleartune.core.model.LocationId
import com.cleartune.core.model.LocationType
import com.cleartune.core.model.MusicSource
import com.cleartune.core.model.SourceMutation
import com.cleartune.core.model.SourceType
import com.cleartune.core.model.Track
import com.cleartune.core.model.TrackLocation
import com.cleartune.core.model.QueueCommand
import com.cleartune.core.model.PlaylistCommand
import com.cleartune.core.model.PlaylistId
import com.cleartune.core.model.PlaylistItemId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomLibraryTransactionTest {
    private lateinit var database: ClearTuneDatabase
    private lateinit var repository: RoomLibraryRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ClearTuneDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(ClearTuneDatabase.LOCAL_SOURCE_CALLBACK)
            .build()
        repository = RoomLibraryRepository(database)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun no_op_rescan_has_no_track_or_location_updates_and_preserves_added_time() = runBlocking {
        val initial = record(title = "Track", addedAtEpochMs = 100)
        val first = repository.applyLocalSnapshot(SOURCE, "本地音乐", listOf(initial), 100)
        val second = repository.applyLocalSnapshot(
            SOURCE,
            "本地音乐",
            listOf(initial.copy(addedAtEpochMs = 999)),
            999,
        )

        assertEquals(1, first.inserted)
        assertEquals(0, second.updated)
        assertEquals(100L, database.libraryReadDao().track(repository.observeSongs().first().single().id.value)?.addedAtEpochMs)
        assertEquals(1, database.libraryWriteDao().latestSyncSession()?.processed)
    }

    @Test
    fun version_one_contains_product_tables_and_creates_local_source_once() = runBlocking {
        val cursor = database.openHelper.writableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'table'",
        )
        val tables = buildSet {
            cursor.use {
                while (it.moveToNext()) add(it.getString(0))
            }
        }

        assertEquals(
            true,
            tables.containsAll(
                setOf(
                    "playlists",
                    "playlist_tracks",
                    "playback_history",
                    "downloads",
                    "playback_queues",
                    "playback_queue_items",
                    "playback_state",
                    "sync_sessions",
                ),
            ),
        )
        assertEquals("本地音乐", database.sourceDao().source(SOURCE.value)?.name)
    }

    @Test
    fun changed_metadata_updates_the_existing_stable_track() = runBlocking {
        repository.applyLocalSnapshot(SOURCE, "本地音乐", listOf(record()), 100)
        val originalId = repository.observeSongs().first().single().id

        val result = repository.applyLocalSnapshot(SOURCE, "本地音乐", listOf(record(title = "Renamed")), 200)

        val song = repository.observeSongs().first().single()
        assertEquals(1, result.updated)
        assertEquals(originalId, song.id)
        assertEquals("Renamed", song.title)
    }

    @Test
    fun local_rescan_does_not_cascade_delete_another_source_location() = runBlocking {
        repository.applyLocalSnapshot(SOURCE, "本地音乐", listOf(record()), 100)
        val trackId = repository.observeSongs().first().single().id
        val remoteSource = SourceId("webdav-home")
        repository.applySourceMutation(
            SourceMutation.Upsert(MusicSource(remoteSource, "家庭音乐库", SourceType.WEBDAV, baseUrl = "https://example.test/")),
        )
        repository.applyLibraryMutation(
            LibraryMutation.Upsert(
                sourceId = remoteSource,
                tracks = listOf(Track(trackId, "Track", addedAtEpochMs = 100)),
                locations = listOf(
                    TrackLocation(
                        id = LocationId("remote-location"),
                        trackId = trackId,
                        sourceId = remoteSource,
                        sourceKey = "Music/Track.mp3",
                        type = LocationType.REMOTE_URL,
                        uri = "https://example.test/Music/Track.mp3",
                    ),
                ),
            ),
        )

        repository.applyLocalSnapshot(SOURCE, "本地音乐", listOf(record(title = "Renamed")), 200)

        assertEquals(2, database.libraryReadDao().playableLocations(trackId.value).size)
    }

    @Test
    fun missing_location_is_marked_unavailable_without_deleting_product_rows() = runBlocking {
        val seeded = seedProductGraph()

        repository.applyLocalSnapshot(SOURCE, "Local music", emptyList(), 200)

        assertEquals(
            false,
            database.libraryWriteDao().locationIncludingUnavailable(SOURCE.value, record().sourceKey)?.available,
        )
        assertEquals(seeded.trackId, database.libraryReadDao().track(seeded.trackId.value)?.id)
        assertEquals(seeded.trackId.value, database.playlistDao().items(seeded.playlistId.value).single().trackId)
        assertEquals(seeded.trackId.value, database.playbackDao().queueItems().single().trackId)
        assertEquals(seeded.trackId.value, database.downloadDao().download("download-1")?.trackId)
        assertEquals(seeded.trackId.value, database.playbackDao().observeRecentHistory(1).first().single().trackId)
    }

    @Test
    fun reappearing_location_reactivates_existing_track_identity() = runBlocking {
        val seeded = seedProductGraph()
        val originalLocationId = database.libraryWriteDao()
            .locationIncludingUnavailable(SOURCE.value, record().sourceKey)!!.id
        repository.applyLocalSnapshot(SOURCE, "Local music", emptyList(), 200)

        repository.applyLocalSnapshot(SOURCE, "Local music", listOf(record(title = "Reappeared")), 300)

        val reactivated = database.libraryWriteDao()
            .locationIncludingUnavailable(SOURCE.value, record().sourceKey)!!
        assertEquals(originalLocationId, reactivated.id)
        assertEquals(seeded.trackId.value, reactivated.trackId)
        assertEquals(true, reactivated.available)
        assertEquals(seeded.trackId, repository.observeSongs().first().single().id)
    }

    @Test
    fun cross_source_upsert_updates_search_and_orphan_removal_cleans_it() = runBlocking {
        val remoteSource = SourceId("webdav-home")
        repository.applySourceMutation(
            SourceMutation.Upsert(MusicSource(remoteSource, "Remote", SourceType.WEBDAV, baseUrl = "https://example.test/")),
        )
        val trackId = com.cleartune.core.model.TrackId("remote-track")
        repository.applyLibraryMutation(
            LibraryMutation.Upsert(
                sourceId = remoteSource,
                tracks = listOf(Track(trackId, "Aurora Signal")),
                locations = listOf(
                    TrackLocation(
                        LocationId("remote-location"), trackId, remoteSource, "Music/Aurora.mp3",
                        LocationType.REMOTE_URL, "https://example.test/Music/Aurora.mp3",
                    ),
                ),
            ),
        )

        assertEquals(trackId, repository.search("Aurora").first().songs.single().id)
        repository.applyLibraryMutation(LibraryMutation.RetainSourceKeys(remoteSource, emptySet()))
        assertEquals(emptyList<com.cleartune.core.model.TrackSummary>(), repository.search("Aurora").first().songs)
    }

    @Test
    fun webDavUpsertPreservesNestedFolderHierarchyAndModifiedValidator() = runBlocking {
        val remoteSource = SourceId("webdav-home")
        repository.applySourceMutation(
            SourceMutation.Upsert(MusicSource(remoteSource, "Remote", SourceType.WEBDAV, baseUrl = "https://example.test/")),
        )
        val trackId = com.cleartune.core.model.TrackId("remote-track")

        repository.applyLibraryMutation(
            LibraryMutation.Upsert(
                sourceId = remoteSource,
                tracks = listOf(Track(trackId, "Song")),
                locations = listOf(
                    TrackLocation(
                        id = LocationId("remote-location"),
                        trackId = trackId,
                        sourceId = remoteSource,
                        sourceKey = "Artist/Album/song.flac",
                        type = LocationType.REMOTE_URL,
                        uri = "https://example.test/Artist/Album/song.flac",
                        relativeFolder = "Artist/Album",
                        modifiedEpochMs = 9_000,
                    ),
                ),
            ),
        )

        assertEquals(listOf("Artist/Album"), repository.observeFolders().first().map { it.relativeFolder })
        assertEquals(listOf(trackId), repository.observeFolderTracks("Artist/Album").first().map { it.id })
        val location = requireNotNull(database.libraryWriteDao().locationIncludingUnavailable(remoteSource.value, "Artist/Album/song.flac"))
        assertEquals(9L, location.modifiedEpochSeconds)
    }

    @Test
    fun queue_and_playlist_contract_adapters_persist_commands() = runBlocking {
        repository.applyLocalSnapshot(SOURCE, "Local music", listOf(record()), 100)
        val trackId = repository.observeSongs().first().single().id
        val queueRepository = RoomQueueRepository(database, idFactory = { "queue-item-1" }, clock = { 200 })
        val playlistRepository = RoomPlaylistRepository(database, idFactory = { "playlist-id" }, clock = { 200 })

        queueRepository.apply(QueueCommand.Replace(listOf(trackId)))
        playlistRepository.apply(PlaylistCommand.Create("Favorites"))
        val playlistId = playlistRepository.observePlaylists().first().single().id
        playlistRepository.apply(PlaylistCommand.AddTrack(playlistId, trackId))

        assertEquals(trackId, queueRepository.observeQueue().first().items.single().trackId)
        assertEquals(1, playlistRepository.observePlaylists().first().single().trackCount)
    }

    @Test
    fun recent_playback_history_projects_available_tracks_in_history_order() = runBlocking {
        repository.applyLocalSnapshot(
            SOURCE,
            "Local music",
            listOf(
                record(title = "Older"),
                record(title = "Newest").copy(
                    sourceKey = "mediastore:2",
                    uri = "content://media/external/audio/media/2",
                    displayName = "Newest.mp3",
                ),
            ),
            100,
        )
        val tracks = repository.observeSongs().first().associateBy { it.title }
        database.playbackDao().upsertHistory(
            PlaybackHistoryEntity(trackId = tracks.getValue("Older").id.value, playedAtEpochMs = 100, completed = false),
        )
        database.playbackDao().upsertHistory(
            PlaybackHistoryEntity(trackId = tracks.getValue("Newest").id.value, playedAtEpochMs = 200, completed = true),
        )

        assertEquals(listOf("Newest", "Older"), repository.observeLibraryHome().first().recentPlayed.map { it.title })
    }

    @Test
    fun favorites_have_fixed_identity_idempotent_order_and_survive_unavailable_locations() = runBlocking {
        repository.applyLocalSnapshot(SOURCE, "Local music", listOf(record(), record().copy(sourceKey = "second", uri = "content://audio/2", displayName = "second.mp3", title = "Second")), 100)
        val tracks = repository.observeSongs().first()
        val favorites = RoomFavoritesRepository(database, clock = { 200 })

        favorites.setFavorite(tracks[0].id, true)
        favorites.setFavorite(tracks[0].id, true)
        favorites.setFavorite(tracks[1].id, true)

        assertEquals(RoomFavoritesRepository.FAVORITES_ID, database.playlistDao().playlist(RoomFavoritesRepository.FAVORITES_ID)?.id)
        assertEquals(listOf(tracks[0].id, tracks[1].id), favorites.observeFavorites().first())
        assertTrue(favorites.observeIsFavorite(tracks[0].id).first())

        repository.applyLibraryMutation(LibraryMutation.RetainSourceKeys(SOURCE, emptySet()))
        assertEquals(listOf(tracks[0].id, tracks[1].id), favorites.observeFavorites().first())

        favorites.setFavorite(tracks[0].id, false)
        favorites.setFavorite(tracks[0].id, false)
        assertEquals(listOf(tracks[1].id), favorites.observeFavorites().first())
    }

    @Test
    fun failed_cross_source_mutation_rolls_back_all_prior_writes() = runBlocking {
        val remoteSource = SourceId("webdav-home")
        repository.applySourceMutation(
            SourceMutation.Upsert(MusicSource(remoteSource, "Remote", SourceType.WEBDAV, baseUrl = "https://example.test/")),
        )
        val trackId = com.cleartune.core.model.TrackId("would-be-rolled-back")

        runCatching {
            repository.applyLibraryMutation(
                LibraryMutation.Upsert(
                    sourceId = remoteSource,
                    tracks = listOf(Track(trackId, "Transient")),
                    locations = listOf(
                        TrackLocation(
                            LocationId("invalid-location"),
                            com.cleartune.core.model.TrackId("missing-track"),
                            remoteSource,
                            "missing.mp3",
                            LocationType.REMOTE_URL,
                            "https://example.test/missing.mp3",
                        ),
                    ),
                ),
            )
        }.onSuccess { error("Expected foreign-key failure") }

        assertNull(database.libraryReadDao().track(trackId.value))
    }

    @Test
    fun removing_current_queue_item_preserves_shuffle_order_and_resets_position() = runBlocking {
        repository.applyLocalSnapshot(SOURCE, "Local music", listOf(record()), 100)
        val trackId = repository.observeSongs().first().single().id
        val ids = ArrayDeque(listOf("first", "second", "third"))
        val queueRepository = RoomQueueRepository(database, idFactory = { ids.removeFirst() }, clock = { 200 })
        queueRepository.apply(QueueCommand.Replace(listOf(trackId, trackId, trackId), startIndex = 1))
        database.playbackDao().upsertPlaybackState(
            database.playbackDao().playbackState()!!.copy(
                positionMs = 999,
                shuffleEnabled = true,
                shuffleOrder = "third\u001ffirst\u001fsecond",
            ),
        )

        queueRepository.apply(QueueCommand.Remove(com.cleartune.core.model.QueueItemId("second")))

        val state = database.playbackDao().playbackState()!!
        assertEquals(0L, state.positionMs)
        assertEquals("third\u001ffirst", state.shuffleOrder)
    }

    @Test
    fun playlist_remove_cannot_delete_an_item_from_another_playlist() = runBlocking {
        repository.applyLocalSnapshot(SOURCE, "Local music", listOf(record()), 100)
        val trackId = repository.observeSongs().first().single().id
        val ids = ArrayDeque(listOf("playlist-a", "playlist-b", "item-a"))
        val playlists = RoomPlaylistRepository(database, idFactory = { ids.removeFirst() }, clock = { 200 })
        playlists.apply(PlaylistCommand.Create("A"))
        playlists.apply(PlaylistCommand.Create("B"))
        playlists.apply(PlaylistCommand.AddTrack(PlaylistId("playlist-a"), trackId))

        playlists.apply(PlaylistCommand.RemoveTrack(PlaylistId("playlist-b"), PlaylistItemId("item-a")))

        assertEquals(1, database.playlistDao().items("playlist-a").size)
    }

    private fun RoomLibraryRepository.observeSongs() = observeSongs(com.cleartune.core.model.SongQuery())

    private suspend fun seedProductGraph(): SeededProductGraph {
        repository.applyLocalSnapshot(SOURCE, "Local music", listOf(record()), 100)
        val trackId = repository.observeSongs().first().single().id
        val queueRepository = RoomQueueRepository(database, idFactory = { "queue-item-1" }, clock = { 100 })
        val playlistRepository = RoomPlaylistRepository(database, idFactory = ArrayDeque(listOf("playlist-1", "playlist-item-1"))::removeFirst, clock = { 100 })
        queueRepository.apply(QueueCommand.Replace(listOf(trackId)))
        playlistRepository.apply(PlaylistCommand.Create("Retained"))
        val playlistId = playlistRepository.observePlaylists().first().single().id
        playlistRepository.apply(PlaylistCommand.AddTrack(playlistId, trackId))
        database.downloadDao().upsert(
            DownloadEntity(
                id = "download-1",
                trackId = trackId.value,
                state = "COMPLETED",
                bytesDownloaded = 10,
                totalBytes = 10,
                etag = null,
                partialPath = null,
                finalPath = "/downloads/Track.mp3",
                errorMessage = null,
                updatedAtEpochMs = 100,
            ),
        )
        database.playbackDao().upsertHistory(
            PlaybackHistoryEntity(trackId = trackId.value, playedAtEpochMs = 100, completed = true),
        )
        return SeededProductGraph(trackId, playlistId)
    }

    private fun record(
        title: String = "Track",
        addedAtEpochMs: Long = 100,
    ) = LibraryIngestRecord(
        sourceKey = "mediastore:1",
        uri = "content://media/external/audio/media/1",
        displayName = "Track.mp3",
        relativeFolder = "Music",
        title = title,
        albumTitle = "Album",
        artistNames = listOf("Artist"),
        durationMs = 1_000,
        artworkRef = null,
        sizeBytes = 10,
        modifiedEpochSeconds = 1,
        addedAtEpochMs = addedAtEpochMs,
    )

    private companion object {
        val SOURCE = SourceId("local")
    }

    private data class SeededProductGraph(
        val trackId: com.cleartune.core.model.TrackId,
        val playlistId: PlaylistId,
    )
}
