package com.cleartune.playback

import com.cleartune.core.contracts.PlaybackLibraryRepository
import com.cleartune.core.model.LocationId
import com.cleartune.core.model.LocationType
import com.cleartune.core.model.PlayableTrack
import com.cleartune.core.model.PlaybackCommand
import com.cleartune.core.model.RepeatMode
import com.cleartune.core.model.QueueCommand
import com.cleartune.core.model.SourceId
import com.cleartune.core.model.Track
import com.cleartune.core.model.TrackId
import com.cleartune.core.model.TrackLocation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCoordinatorTest {
    private val trackId = TrackId("track")

    @Test
    fun failed_download_attempt_falls_back_to_remote_without_exposing_uri() = runTest {
        val backend = RecordingBackend(failingLocationIds = setOf("download"))
        val coordinator = coordinator(
            backend = backend,
            locations = listOf(
                location("download", LocationType.DOWNLOADED_FILE, "/private/offline/song.mp3"),
                location("remote", LocationType.REMOTE_URL, "https://secret.example/song.mp3"),
            ),
        )

        coordinator.dispatch(PlaybackCommand.PlayTrack(trackId))

        assertEquals(listOf("download", "remote"), backend.loadAttempts)
        assertTrue(coordinator.state.value.isPlaying)
        assertEquals("Song", coordinator.state.value.currentTrack?.title)
        assertNull(coordinator.state.value.errorMessage)
        assertFalse(coordinator.state.value.toString().contains("secret.example"))
    }

    @Test
    fun missing_track_produces_plain_language_error() = runTest {
        val coordinator = PlaybackCoordinator(
            libraryRepository = repository(null),
            queueRepository = InMemoryQueueRepository(),
            backend = RecordingBackend(),
            environment = availableEnvironment(),
        )

        coordinator.dispatch(PlaybackCommand.PlayTrack(trackId))

        assertEquals("找不到这首歌曲", coordinator.state.value.errorMessage)
        assertFalse(coordinator.state.value.isPlaying)
    }

    @Test
    fun seek_is_clamped_to_track_duration() = runTest {
        val backend = RecordingBackend()
        val coordinator = coordinator(backend)
        coordinator.dispatch(PlaybackCommand.PlayTrack(trackId))

        coordinator.dispatch(PlaybackCommand.SeekTo(99_000))

        assertEquals(30_000L, backend.lastSeekPositionMs)
        assertEquals(30_000L, coordinator.state.value.positionMs)
    }

    @Test
    fun pause_repeat_and_shuffle_are_reflected_in_state() = runTest {
        val backend = RecordingBackend()
        val coordinator = coordinator(backend)
        coordinator.dispatch(PlaybackCommand.PlayTrack(trackId))

        coordinator.dispatch(PlaybackCommand.Pause)
        coordinator.dispatch(PlaybackCommand.SetRepeat(RepeatMode.ONE))
        coordinator.dispatch(PlaybackCommand.SetShuffle(true))

        assertFalse(coordinator.state.value.isPlaying)
        assertEquals(RepeatMode.ONE, coordinator.state.value.repeatMode)
        assertTrue(coordinator.state.value.shuffleEnabled)
    }

    @Test
    fun playing_preloaded_first_item_preserves_the_rest_of_queue() = runTest {
        val queue = InMemoryQueueRepository()
        queue.apply(QueueCommand.Replace(listOf(trackId, TrackId("next"))))
        val coordinator = PlaybackCoordinator(
            libraryRepository = repository(
                PlayableTrack(Track(trackId, "Song"), listOf(location("local", LocationType.LOCAL_URI, "content://music/song"))),
            ),
            queueRepository = queue,
            backend = RecordingBackend(),
            environment = availableEnvironment(),
        )

        coordinator.dispatch(PlaybackCommand.PlayTrack(trackId))

        assertEquals(listOf(trackId, TrackId("next")), queue.observeQueue().first().items.map { it.trackId })
    }

    @Test
    fun system_session_events_are_reflected_in_gateway_state() {
        val backend = ObservableRecordingBackend()
        val coordinator = coordinator(backend)

        backend.emit(
            BackendPlaybackSnapshot(
                connected = true,
                mediaId = "track",
                title = "Song",
                isPlaying = true,
                positionMs = 12_000,
                durationMs = 30_000,
                repeatMode = RepeatMode.ALL,
                shuffleEnabled = true,
            ),
        )

        assertTrue(coordinator.state.value.isPlaying)
        assertEquals(12_000, coordinator.state.value.positionMs)
        assertEquals(RepeatMode.ALL, coordinator.state.value.repeatMode)
    }

    private fun coordinator(
        backend: RecordingBackend,
        locations: List<TrackLocation> = listOf(location("local", LocationType.LOCAL_URI, "content://music/song")),
    ) = PlaybackCoordinator(
        libraryRepository = repository(PlayableTrack(Track(trackId, "Song", durationMs = 30_000), locations)),
        queueRepository = InMemoryQueueRepository(),
        backend = backend,
        environment = availableEnvironment(),
    )

    private fun availableEnvironment() = PlaybackEnvironment(
        fileExists = { true },
        uriReadable = { true },
        networkAvailable = { true },
    )

    private fun repository(track: PlayableTrack?) = object : PlaybackLibraryRepository {
        override suspend fun getPlayableTrack(trackId: TrackId): PlayableTrack? = track
    }

    private fun location(id: String, type: LocationType, uri: String) = TrackLocation(
        id = LocationId(id),
        trackId = trackId,
        sourceId = SourceId("source"),
        sourceKey = id,
        type = type,
        uri = uri,
    )

    private open class RecordingBackend(
        private val failingLocationIds: Set<String> = emptySet(),
    ) : PlaybackBackend {
        override val connected: Boolean = true
        val loadAttempts = mutableListOf<String>()
        var lastSeekPositionMs: Long? = null

        override suspend fun load(track: Track, location: TrackLocation) {
            loadAttempts += location.id.value
            if (location.id.value in failingLocationIds) error("Backend rejected private location")
        }

        override suspend fun play() = Unit
        override suspend fun pause() = Unit
        override suspend fun next() = Unit
        override suspend fun previous() = Unit
        override suspend fun seekTo(positionMs: Long) { lastSeekPositionMs = positionMs }
        override suspend fun setRepeat(mode: RepeatMode) = Unit
        override suspend fun setShuffle(enabled: Boolean) = Unit
    }

    private class ObservableRecordingBackend : RecordingBackend(), ObservablePlaybackBackend {
        private var observer: ((BackendPlaybackSnapshot) -> Unit)? = null
        override fun setPlaybackObserver(observer: (BackendPlaybackSnapshot) -> Unit) {
            this.observer = observer
        }
        fun emit(snapshot: BackendPlaybackSnapshot) = observer?.invoke(snapshot)
    }
}
