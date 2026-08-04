package com.cleartune.playback

import androidx.media3.common.PlaybackException
import com.cleartune.core.contracts.PlaybackLibraryRepository
import com.cleartune.core.model.LocationId
import com.cleartune.core.model.LocationType
import com.cleartune.core.model.PlayableTrack
import com.cleartune.core.model.PlaybackCommand
import com.cleartune.core.model.QueueCommand
import com.cleartune.core.model.QueueItemId
import com.cleartune.core.model.RepeatMode
import com.cleartune.core.model.SourceId
import com.cleartune.core.model.Track
import com.cleartune.core.model.TrackId
import com.cleartune.core.model.TrackLocation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaybackRecoveryTest {
    @Test
    fun `recovery prepares persisted queue but clears stale play intent`() = runTest {
        val storage = RecoveryMemoryStorage()
        val queue = PersistentQueueRepository(storage) { QueueItemId("occurrence") }
        queue.apply(QueueCommand.Replace(listOf(TrackId("track"))))
        queue.updatePlaybackState(positionMs = 4_200, playWhenReady = true, repeatMode = RepeatMode.ALL)
        val backend = RecoveryBackend(
            initiallyPlayWhenReady = true,
            persistedPlayWhenReady = storage::persistedPlayWhenReady,
        )
        val coordinator = coordinator(queue, backend)

        coordinator.syncQueue()

        assertEquals(1, backend.loadQueueCalls)
        assertEquals(4_200L, backend.loadedPositionMs)
        assertEquals(1, backend.pauseCalls)
        assertEquals(0, backend.playCalls)
        assertFalse(PersistentQueueRepository(storage).observeQueue().first().playWhenReady)
        assertEquals(
            listOf("pause:persisted=false", "load:playWhenReady=false"),
            backend.recoveryEvents,
        )
    }

    @Test
    fun `next and previous persist the active queue occurrence`() = runTest {
        val storage = RecoveryMemoryStorage()
        var nextId = 0
        val queue = PersistentQueueRepository(storage) { QueueItemId((++nextId).toString()) }
        queue.apply(QueueCommand.Replace(listOf(TrackId("track"), TrackId("track"))))
        val coordinator = coordinator(queue, RecoveryBackend())

        coordinator.dispatch(PlaybackCommand.Next)
        assertEquals(1, PersistentQueueRepository(storage).observeQueue().first().currentIndex)

        coordinator.dispatch(PlaybackCommand.Previous)
        assertEquals(0, PersistentQueueRepository(storage).observeQueue().first().currentIndex)
    }

    @Test
    fun `next and previous persist repeat-all wraparound occurrence`() = runTest {
        val storage = RecoveryMemoryStorage()
        var nextId = 0
        val queue = PersistentQueueRepository(storage) { QueueItemId((++nextId).toString()) }
        queue.apply(QueueCommand.Replace(listOf(TrackId("track"), TrackId("track"))))
        queue.updatePlaybackState(repeatMode = RepeatMode.ALL)
        val coordinator = coordinator(queue, RecoveryBackend())

        coordinator.dispatch(PlaybackCommand.Previous)
        assertEquals(1, PersistentQueueRepository(storage).observeQueue().first().currentIndex)

        coordinator.dispatch(PlaybackCommand.Next)
        assertEquals(0, PersistentQueueRepository(storage).observeQueue().first().currentIndex)
    }

    @Test
    fun `asynchronous item failure advances to the next sanitized location`() = runTest {
        val backend = AsyncRecoveryBackend()
        val coordinator = asyncCoordinator(backend, this)
        coordinator.dispatch(PlaybackCommand.PlayTrack(TrackId("track")))

        backend.emitFailure(BackendPlaybackFailure.Item("This copy could not be read"))
        advanceUntilIdle()

        assertEquals(listOf("download", "remote"), backend.loadAttempts)
        assertEquals(2, backend.playCalls)
        assertTrue(coordinator.state.value.isPlaying)
        assertFalse(coordinator.state.value.toString().contains("secret.example"))
    }

    @Test
    fun `asynchronous global failure pauses without advancing location`() = runTest {
        val backend = AsyncRecoveryBackend()
        val coordinator = asyncCoordinator(backend, this)
        coordinator.dispatch(PlaybackCommand.PlayTrack(TrackId("track")))

        backend.emitFailure(BackendPlaybackFailure.Global("Secure connection failed"))
        advanceUntilIdle()

        assertEquals(listOf("download"), backend.loadAttempts)
        assertEquals(1, backend.pauseCalls)
        assertFalse(coordinator.state.value.isPlaying)
        assertEquals("Secure connection failed", coordinator.state.value.errorMessage)
    }

    @Test
    fun `restored queue item falls back to its second sanitized location`() = runTest {
        val storage = RecoveryMemoryStorage()
        val queue = PersistentQueueRepository(storage) { QueueItemId("occurrence-a") }
        queue.apply(QueueCommand.Replace(listOf(TrackId("a"))))
        val backend = SnapshotQueueBackend()
        val coordinator = queueCoordinator(queue, backend, this, mapOf("a" to playable("a")))
        coordinator.syncQueue()

        backend.emitFailure("occurrence-a", "a")
        advanceUntilIdle()

        assertEquals(listOf("occurrence-a" to "a-remote"), backend.replacements)
    }

    @Test
    fun `natural advancement selects the new occurrence before fallback`() = runTest {
        val storage = RecoveryMemoryStorage()
        var nextId = 0
        val queue = PersistentQueueRepository(storage) { QueueItemId("occurrence-${++nextId}") }
        queue.apply(QueueCommand.Replace(listOf(TrackId("a"), TrackId("b"))))
        val backend = SnapshotQueueBackend()
        val coordinator = queueCoordinator(
            queue,
            backend,
            this,
            mapOf("a" to playable("a"), "b" to playable("b")),
        )
        coordinator.syncQueue()

        backend.emitSnapshot("occurrence-2", "b", positionMs = 120, playWhenReady = true)
        advanceUntilIdle()
        backend.emitFailure("occurrence-2", "b")
        advanceUntilIdle()

        assertEquals(listOf("occurrence-2" to "b-remote"), backend.replacements)
    }

    @Test
    fun `stale failure for prior occurrence is ignored`() = runTest {
        val storage = RecoveryMemoryStorage()
        var nextId = 0
        val queue = PersistentQueueRepository(storage) { QueueItemId("occurrence-${++nextId}") }
        queue.apply(QueueCommand.Replace(listOf(TrackId("a"), TrackId("b"))))
        val backend = SnapshotQueueBackend()
        val coordinator = queueCoordinator(
            queue,
            backend,
            this,
            mapOf("a" to playable("a"), "b" to playable("b")),
        )
        coordinator.syncQueue()

        backend.emitSnapshot("occurrence-2", "b", positionMs = 120, playWhenReady = true)
        advanceUntilIdle()
        backend.emitFailure("occurrence-1", "a")
        advanceUntilIdle()

        assertTrue(backend.replacements.isEmpty())
        assertEquals("b", coordinator.state.value.currentTrack?.id?.value)
    }

    @Test
    fun `latest external snapshot persists occurrence position and play intent`() = runTest {
        val storage = RecoveryMemoryStorage()
        var nextId = 0
        val queue = PersistentQueueRepository(storage) { QueueItemId("occurrence-${++nextId}") }
        queue.apply(QueueCommand.Replace(listOf(TrackId("a"), TrackId("b"))))
        val backend = SnapshotQueueBackend()
        val coordinator = queueCoordinator(
            queue,
            backend,
            this,
            mapOf("a" to playable("a"), "b" to playable("b")),
        )
        coordinator.syncQueue()

        backend.emitSnapshot("occurrence-1", "a", positionMs = 100, playWhenReady = false)
        backend.emitSnapshot("occurrence-2", "b", positionMs = 250, playWhenReady = true)
        backend.emitSnapshot(
            "occurrence-2",
            "b",
            positionMs = 900,
            playWhenReady = true,
            repeatMode = RepeatMode.ALL,
            shuffleEnabled = true,
        )
        advanceUntilIdle()

        val restored = PersistentQueueRepository(storage).observeQueue().first()
        assertEquals(1, restored.currentIndex)
        assertEquals(900L, restored.positionMs)
        assertTrue(restored.playWhenReady)
        assertEquals(RepeatMode.ALL, restored.repeatMode)
        assertTrue(restored.shuffleEnabled)
    }

    @Test
    fun `live shuffle reloads backend occurrence order without losing position or play state`() = runTest {
        val storage = RecoveryMemoryStorage()
        var nextId = 0
        val queue = PersistentQueueRepository(
            storage = storage,
            createShuffleOrder = { occurrences -> occurrences.reversed() },
            createId = { QueueItemId("occurrence-${++nextId}") },
        )
        queue.apply(QueueCommand.Replace(listOf(TrackId("a"), TrackId("b"), TrackId("c"))))
        val backend = SnapshotQueueBackend()
        val coordinator = queueCoordinator(
            queue,
            backend,
            this,
            mapOf("a" to playable("a"), "b" to playable("b"), "c" to playable("c")),
        )
        backend.emitSnapshot("occurrence-2", "b", positionMs = 777, playWhenReady = true)
        advanceUntilIdle()

        coordinator.dispatch(PlaybackCommand.SetShuffle(true))

        assertEquals(listOf("occurrence-3", "occurrence-2", "occurrence-1"), backend.loadedOccurrences)
        assertEquals(1, backend.loadedStartIndex)
        assertEquals(777L, backend.loadedPositionMs)
        assertEquals(1, backend.playCalls)
        assertTrue(PersistentQueueRepository(storage).observeQueue().first().shuffleEnabled)
        assertTrue(PersistentQueueRepository(storage).observeQueue().first().playWhenReady)
    }

    @Test
    fun `location fallback never consumes coroutine cancellation`() = runTest {
        val backend = AsyncRecoveryBackend(cancelLoad = true)
        val coordinator = asyncCoordinator(backend, this)

        var cancelled = false
        try {
            coordinator.dispatch(PlaybackCommand.PlayTrack(TrackId("track")))
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertEquals(listOf("download"), backend.loadAttempts)
    }

    @Test
    fun `Media3 item read not found and decoder failures may advance location`() {
        val itemCodes = listOf(
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        )

        itemCodes.forEach { code ->
            assertTrue(Media3ErrorClassifier.classify(code, null) is BackendPlaybackFailure.Item)
        }
        assertTrue(
            Media3ErrorClassifier.classify(
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                null,
                httpStatus = 404,
            ) is BackendPlaybackFailure.Item,
        )
    }

    @Test
    fun `Media3 network authentication TLS and unknown failures remain global`() {
        val globalErrors = listOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED to null,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT to null,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED to javax.net.ssl.SSLHandshakeException("certificate rejected"),
            PlaybackException.ERROR_CODE_UNSPECIFIED to null,
        )

        globalErrors.forEach { (code, cause) ->
            assertTrue(Media3ErrorClassifier.classify(code, cause) is BackendPlaybackFailure.Global)
        }
        assertTrue(
            Media3ErrorClassifier.classify(
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                null,
                httpStatus = 401,
            ) is BackendPlaybackFailure.Global,
        )
    }

    @Test
    fun `Media3 classifier preserves cancellation`() {
        var cancelled = false
        try {
            Media3ErrorClassifier.classify(
                PlaybackException.ERROR_CODE_UNSPECIFIED,
                CancellationException("cancelled"),
            )
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    @Test
    fun `approved library children are playable and media ids resolve for playback`() {
        val callback = ClearTuneLibrarySessionCallback(
            catalog = object : LibrarySessionCatalog {
                override fun children(parentId: String) = if (parentId == "songs") listOf(catalogTrack()) else emptyList()
                override fun resolve(mediaId: String) = catalogTrack().takeIf { it.mediaId == mediaId }
            },
        )

        val children = callback.describeChildren("songs", page = 0, pageSize = 10)
        val resolved = callback.describeForPlayback(listOf("track", "missing"))

        assertEquals(1, children.size)
        assertTrue(children.single().playable)
        assertEquals("track", children.single().mediaId)
        assertEquals(listOf("track"), resolved.map { it.mediaId })
        assertTrue(resolved.single().playbackUri?.startsWith("cleartune-media://") == true)
        assertFalse(resolved.single().toString().contains("secret.example"))
        assertTrue(callback.describeChildren("unapproved", 0, 10).isEmpty())
    }

    @Test
    fun `library metadata sanitizes title artist album and artwork`() {
        val metadata = MediaItemFactory.sanitizeMetadata(
            title = " \u0000Song\n ",
            artist = " Artist\tName ",
            album = " Album\rName ",
            artworkUri = "https://user:password@private.example/cover.jpg",
        )

        assertEquals("Song", metadata.title)
        assertEquals("Artist Name", metadata.artist)
        assertEquals("Album Name", metadata.album)
        assertNull(metadata.artworkUri)
    }

    @Test
    fun `private registry evicts old mappings and queue replacement clears stale entries`() {
        PrivateMediaSourceRegistry.clear()
        val first = PrivateMediaSourceRegistry.register("track-0", "https://private.example/0.mp3")
        repeat(256) { index ->
            PrivateMediaSourceRegistry.register("track-${index + 1}", "https://private.example/${index + 1}.mp3")
        }
        assertNull(PrivateMediaSourceRegistry.resolve(first))

        val replacement = PrivateMediaSourceRegistry.replace(
            listOf("replacement" to "https://private.example/replacement.mp3"),
        ).single()
        assertEquals("https://private.example/replacement.mp3", PrivateMediaSourceRegistry.resolve(replacement))
        assertNull(PrivateMediaSourceRegistry.resolve(
            PrivateMediaSourceRegistry.opaqueUri("track-256", "https://private.example/256.mp3"),
        ))
    }

    @Test
    fun `library browsing does not preregister private playback locations`() {
        PrivateMediaSourceRegistry.clear()
        val callback = ClearTuneLibrarySessionCallback(
            catalog = object : LibrarySessionCatalog {
                override fun children(parentId: String) = listOf(catalogTrack())
                override fun resolve(mediaId: String) = catalogTrack()
            },
        )

        val child = callback.describeChildren("songs", page = 0, pageSize = 10).single()

        assertNull(child.playbackUri)
        assertNull(
            PrivateMediaSourceRegistry.resolve(
                PrivateMediaSourceRegistry.opaqueUri("track", "https://secret.example/song.mp3"),
            ),
        )
    }

    @Test
    fun `active queue registry entries remain pinned while transient entries are bounded`() {
        PrivateMediaSourceRegistry.clear()
        val active = PrivateMediaSourceRegistry.replace(
            listOf("active" to "https://private.example/active.mp3"),
        ).single()

        repeat(PrivateMediaSourceRegistry.capacity) { index ->
            PrivateMediaSourceRegistry.register("browse-$index", "https://private.example/browse-$index.mp3")
        }

        assertEquals("https://private.example/active.mp3", PrivateMediaSourceRegistry.resolve(active))
        assertTrue(PrivateMediaSourceRegistry.size <= PrivateMediaSourceRegistry.capacity)
    }

    @Test
    fun `queue registry rejects an active set larger than its explicit capacity`() {
        PrivateMediaSourceRegistry.clear()
        val entries = (0..PrivateMediaSourceRegistry.capacity).map { index ->
            "active-$index" to "https://private.example/$index.mp3"
        }

        assertThrows(IllegalArgumentException::class.java) {
            PrivateMediaSourceRegistry.replace(entries)
        }
        assertEquals(0, PrivateMediaSourceRegistry.size)
    }

    private fun catalogTrack() = LibraryCatalogTrack(
        mediaId = "track",
        title = "Song",
        artist = "Artist",
        album = "Album",
        artworkUri = "content://artwork/track",
        playbackUri = "https://secret.example/song.mp3",
        mimeType = "audio/mpeg",
    )

    private fun coordinator(queue: PersistentQueueRepository, backend: RecoveryBackend) = PlaybackCoordinator(
        libraryRepository = object : PlaybackLibraryRepository {
            override suspend fun getPlayableTrack(trackId: TrackId): PlayableTrack = PlayableTrack(
                track = Track(trackId, "Song"),
                locations = listOf(
                    TrackLocation(
                        id = LocationId("local"),
                        trackId = trackId,
                        sourceId = SourceId("source"),
                        sourceKey = "local",
                        type = LocationType.LOCAL_URI,
                        uri = "content://music/song",
                    ),
                ),
            )
        },
        queueRepository = queue,
        backend = backend,
        environment = PlaybackEnvironment({ true }, { true }, { true }),
    )

    private fun asyncCoordinator(
        backend: AsyncRecoveryBackend,
        scope: kotlinx.coroutines.CoroutineScope,
    ) = PlaybackCoordinator(
        libraryRepository = object : PlaybackLibraryRepository {
            override suspend fun getPlayableTrack(trackId: TrackId) = PlayableTrack(
                track = Track(trackId, "Song"),
                locations = listOf(
                    TrackLocation(
                        LocationId("download"), trackId, SourceId("source"), "download",
                        LocationType.DOWNLOADED_FILE, "/private/song.mp3",
                    ),
                    TrackLocation(
                        LocationId("remote"), trackId, SourceId("source"), "remote",
                        LocationType.REMOTE_URL, "https://secret.example/song.mp3",
                    ),
                ),
            )
        },
        queueRepository = InMemoryQueueRepository(),
        backend = backend,
        environment = PlaybackEnvironment({ true }, { true }, { true }),
        scope = scope,
    )

    private fun queueCoordinator(
        queue: PersistentQueueRepository,
        backend: SnapshotQueueBackend,
        scope: kotlinx.coroutines.CoroutineScope,
        tracks: Map<String, PlayableTrack>,
    ) = PlaybackCoordinator(
        libraryRepository = object : PlaybackLibraryRepository {
            override suspend fun getPlayableTrack(trackId: TrackId): PlayableTrack? = tracks[trackId.value]
        },
        queueRepository = queue,
        backend = backend,
        environment = PlaybackEnvironment({ true }, { true }, { true }),
        scope = scope,
    )

    private fun playable(id: String): PlayableTrack {
        val trackId = TrackId(id)
        return PlayableTrack(
            Track(trackId, "Song $id"),
            listOf(
                TrackLocation(
                    LocationId("$id-local"), trackId, SourceId("source"), "$id-local",
                    LocationType.DOWNLOADED_FILE, "/private/$id.mp3",
                ),
                TrackLocation(
                    LocationId("$id-remote"), trackId, SourceId("source"), "$id-remote",
                    LocationType.REMOTE_URL, "https://secret.example/$id.mp3",
                ),
            ),
        )
    }
}

private class SnapshotQueueBackend : PlaybackBackend, QueuePlaybackBackend, ObservablePlaybackBackend {
    override val connected = true
    val replacements = mutableListOf<Pair<String, String>>()
    var loadedOccurrences = emptyList<String>()
    var loadedStartIndex = -1
    var loadedPositionMs = -1L
    var playCalls = 0
    private var observer: ((BackendPlaybackSnapshot) -> Unit)? = null

    override suspend fun loadQueue(entries: List<ResolvedQueueEntry>, startIndex: Int, positionMs: Long) {
        loadedOccurrences = entries.map { it.occurrenceId }
        loadedStartIndex = startIndex
        loadedPositionMs = positionMs
    }
    override suspend fun replaceCurrent(entry: ResolvedQueueEntry, positionMs: Long) {
        replacements += entry.occurrenceId to entry.location.id.value
    }
    override suspend fun load(track: Track, location: TrackLocation) = Unit
    override suspend fun play() { playCalls++ }
    override suspend fun pause() = Unit
    override suspend fun next() = Unit
    override suspend fun previous() = Unit
    override suspend fun seekTo(positionMs: Long) = Unit
    override suspend fun setRepeat(mode: RepeatMode) = Unit
    override suspend fun setShuffle(enabled: Boolean) = Unit
    override fun setPlaybackObserver(observer: (BackendPlaybackSnapshot) -> Unit) { this.observer = observer }

    fun emitSnapshot(
        occurrenceId: String,
        trackId: String,
        positionMs: Long,
        playWhenReady: Boolean,
        repeatMode: RepeatMode = RepeatMode.OFF,
        shuffleEnabled: Boolean = false,
    ) {
        observer?.invoke(
            snapshot(
                occurrenceId,
                trackId,
                positionMs,
                playWhenReady,
                null,
                repeatMode,
                shuffleEnabled,
            ),
        )
    }

    fun emitFailure(occurrenceId: String, trackId: String) {
        observer?.invoke(
            snapshot(
                occurrenceId,
                trackId,
                0,
                true,
                BackendPlaybackFailure.Item("This copy could not be read", occurrenceId),
            ),
        )
    }

    private fun snapshot(
        occurrenceId: String,
        trackId: String,
        positionMs: Long,
        playWhenReady: Boolean,
        failure: BackendPlaybackFailure?,
        repeatMode: RepeatMode = RepeatMode.OFF,
        shuffleEnabled: Boolean = false,
    ) = BackendPlaybackSnapshot(
        connected = true,
        mediaId = trackId,
        occurrenceId = occurrenceId,
        title = "Song $trackId",
        isPlaying = playWhenReady,
        playWhenReady = playWhenReady,
        positionMs = positionMs,
        durationMs = null,
        repeatMode = repeatMode,
        shuffleEnabled = shuffleEnabled,
        failure = failure,
    )
}

private class AsyncRecoveryBackend(
    private val cancelLoad: Boolean = false,
) : PlaybackBackend, ObservablePlaybackBackend {
    override val connected = true
    val loadAttempts = mutableListOf<String>()
    var playCalls = 0
    var pauseCalls = 0
    private var observer: ((BackendPlaybackSnapshot) -> Unit)? = null

    override suspend fun load(track: Track, location: TrackLocation) {
        loadAttempts += location.id.value
        if (cancelLoad) throw CancellationException("cancelled")
    }
    override suspend fun play() { playCalls++ }
    override suspend fun pause() { pauseCalls++ }
    override suspend fun next() = Unit
    override suspend fun previous() = Unit
    override suspend fun seekTo(positionMs: Long) = Unit
    override suspend fun setRepeat(mode: RepeatMode) = Unit
    override suspend fun setShuffle(enabled: Boolean) = Unit
    override fun setPlaybackObserver(observer: (BackendPlaybackSnapshot) -> Unit) { this.observer = observer }

    fun emitFailure(failure: BackendPlaybackFailure) {
        observer?.invoke(
            BackendPlaybackSnapshot(
                connected = true,
                mediaId = "track",
                title = "Song",
                isPlaying = false,
                playWhenReady = true,
                positionMs = 0,
                durationMs = null,
                repeatMode = RepeatMode.OFF,
                shuffleEnabled = false,
                failure = failure,
            ),
        )
    }
}

private class RecoveryBackend(
    initiallyPlayWhenReady: Boolean = false,
    private val persistedPlayWhenReady: () -> Boolean = { false },
) : PlaybackBackend, QueuePlaybackBackend {
    override val connected = true
    var loadQueueCalls = 0
    var loadedPositionMs: Long? = null
    var playCalls = 0
    var pauseCalls = 0
    var playWhenReady = initiallyPlayWhenReady
    val recoveryEvents = mutableListOf<String>()

    override suspend fun loadQueue(entries: List<ResolvedQueueEntry>, startIndex: Int, positionMs: Long) {
        loadQueueCalls++
        loadedPositionMs = positionMs
        recoveryEvents += "load:playWhenReady=$playWhenReady"
    }
    override suspend fun load(track: Track, location: TrackLocation) = Unit
    override suspend fun play() { playCalls++; playWhenReady = true }
    override suspend fun pause() {
        pauseCalls++
        recoveryEvents += "pause:persisted=${persistedPlayWhenReady()}"
        playWhenReady = false
    }
    override suspend fun next() = Unit
    override suspend fun previous() = Unit
    override suspend fun seekTo(positionMs: Long) = Unit
    override suspend fun setRepeat(mode: RepeatMode) = Unit
    override suspend fun setShuffle(enabled: Boolean) = Unit
}

private class RecoveryMemoryStorage : QueueStorage {
    private var state: QueueRecoveryState? = null
    override fun loadRecovery(): QueueRecoveryState? = state
    override fun saveRecovery(state: QueueRecoveryState) { this.state = state }
    override fun load() = state?.snapshot
    override fun save(snapshot: com.cleartune.core.model.QueueSnapshot) {
        state = QueueRecoveryState(snapshot)
    }
    fun persistedPlayWhenReady(): Boolean = state?.snapshot?.playWhenReady ?: false
}
