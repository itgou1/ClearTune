package com.cleartune.playback

import com.cleartune.core.contracts.PlaybackGateway
import com.cleartune.core.contracts.PlaybackLibraryRepository
import com.cleartune.core.contracts.QueueRepository
import com.cleartune.core.model.PlaybackCommand
import com.cleartune.core.model.PlaybackState
import com.cleartune.core.model.QueueCommand
import com.cleartune.core.model.RepeatMode
import com.cleartune.core.model.Track
import com.cleartune.core.model.TrackLocation
import com.cleartune.core.model.TrackSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PlaybackEnvironment(
    val fileExists: (String) -> Boolean,
    val uriReadable: (String) -> Boolean,
    val networkAvailable: () -> Boolean,
)

interface PlaybackBackend {
    val connected: Boolean
    suspend fun load(track: Track, location: TrackLocation)
    suspend fun play()
    suspend fun pause()
    suspend fun next()
    suspend fun previous()
    suspend fun seekTo(positionMs: Long)
    suspend fun setRepeat(mode: RepeatMode)
    suspend fun setShuffle(enabled: Boolean)
}

data class ResolvedQueueEntry(val track: Track, val location: TrackLocation)

interface QueuePlaybackBackend {
    suspend fun loadQueue(entries: List<ResolvedQueueEntry>, startIndex: Int, positionMs: Long)
}

data class BackendPlaybackSnapshot(
    val connected: Boolean,
    val mediaId: String?,
    val title: String?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long?,
    val repeatMode: RepeatMode,
    val shuffleEnabled: Boolean,
    val errorMessage: String? = null,
)

interface ObservablePlaybackBackend {
    fun setPlaybackObserver(observer: (BackendPlaybackSnapshot) -> Unit)
}

class PlaybackCoordinator(
    private val libraryRepository: PlaybackLibraryRepository,
    private val queueRepository: QueueRepository,
    private val backend: PlaybackBackend,
    private val environment: PlaybackEnvironment,
) : PlaybackGateway {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(PlaybackState(connected = backend.connected))
    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    init {
        (backend as? ObservablePlaybackBackend)?.setPlaybackObserver(::onBackendState)
    }

    override suspend fun dispatch(command: PlaybackCommand) {
        mutex.withLock {
            when (command) {
                is PlaybackCommand.PlayTrack -> playTrack(command)
                PlaybackCommand.Play -> {
                    backend.play()
                    queueStateWriter()?.updatePlaybackState(playWhenReady = true)
                    mutableState.value = mutableState.value.copy(isPlaying = true, errorMessage = null)
                }
                PlaybackCommand.Pause -> {
                    backend.pause()
                    queueStateWriter()?.updatePlaybackState(playWhenReady = false)
                    mutableState.value = mutableState.value.copy(isPlaying = false)
                }
                PlaybackCommand.Next -> backend.next()
                PlaybackCommand.Previous -> backend.previous()
                is PlaybackCommand.SeekTo -> seek(command.positionMs)
                is PlaybackCommand.SetRepeat -> {
                    backend.setRepeat(command.mode)
                    queueStateWriter()?.updatePlaybackState(repeatMode = command.mode)
                    mutableState.value = mutableState.value.copy(repeatMode = command.mode)
                }
                is PlaybackCommand.SetShuffle -> {
                    backend.setShuffle(command.enabled)
                    queueStateWriter()?.updatePlaybackState(shuffleEnabled = command.enabled)
                    mutableState.value = mutableState.value.copy(shuffleEnabled = command.enabled)
                }
            }
        }
    }

    suspend fun syncQueue() {
        mutex.withLock {
            syncQueueLocked()
        }
    }

    private suspend fun syncQueueLocked(): Boolean {
        val queueBackend = backend as? QueuePlaybackBackend ?: return false
        val queue = queueRepository.observeQueue().first()
        val resolved = mutableListOf<ResolvedQueueEntry>()
        var resolvedCurrentIndex = -1
        queue.items.forEachIndexed { index, item ->
            val playable = libraryRepository.getPlayableTrack(item.trackId) ?: return@forEachIndexed
            val resolution = LocationResolver.resolve(
                playableTrack = playable,
                fileExists = environment.fileExists,
                uriReadable = environment.uriReadable,
                networkAvailable = environment.networkAvailable(),
            )
            val location = (resolution as? LocationResolution.Ready)?.attempts?.firstOrNull()
                ?: return@forEachIndexed
            if (index == queue.currentIndex) resolvedCurrentIndex = resolved.size
            resolved += ResolvedQueueEntry(playable.track, location)
        }
        if (resolved.isEmpty()) return false
        val startIndex = resolvedCurrentIndex.takeIf { it >= 0 } ?: 0
        queueBackend.loadQueue(resolved, startIndex, queue.positionMs)
        backend.setRepeat(queue.repeatMode)
        backend.setShuffle(queue.shuffleEnabled)
        if (queue.playWhenReady) backend.play() else backend.pause()
        return true
    }

    private suspend fun playTrack(command: PlaybackCommand.PlayTrack) {
        val playable = libraryRepository.getPlayableTrack(command.trackId)
        if (playable == null) {
            mutableState.value = mutableState.value.copy(
                isPlaying = false,
                errorMessage = "找不到这首歌曲",
            )
            return
        }

        val resolution = LocationResolver.resolve(
            playableTrack = playable,
            fileExists = environment.fileExists,
            uriReadable = environment.uriReadable,
            networkAvailable = environment.networkAvailable(),
        )
        if (resolution is LocationResolution.Unavailable) {
            mutableState.value = mutableState.value.copy(
                currentTrack = playable.track.toSummary(),
                isPlaying = false,
                durationMs = playable.track.durationMs,
                errorMessage = when (resolution.failure) {
                    PlaybackFailure.NetworkUnavailable -> "网络不可用，且没有离线副本"
                    PlaybackFailure.NoAvailableLocation -> "当前没有可用的播放位置"
                },
            )
            return
        }

        val attempts = (resolution as LocationResolution.Ready).attempts
        for ((attemptIndex, location) in attempts.withIndex()) {
            try {
                val queue = queueRepository.observeQueue().first()
                if (queue.items.getOrNull(queue.currentIndex)?.trackId != command.trackId) {
                    queueRepository.apply(QueueCommand.Replace(listOf(command.trackId)))
                }
                queueStateWriter()?.updatePlaybackState(playWhenReady = true)
                if (backend is QueuePlaybackBackend && attemptIndex == 0) {
                    if (!syncQueueLocked()) backend.load(playable.track, location)
                } else {
                    backend.load(playable.track, location)
                }
                backend.play()
                mutableState.value = PlaybackState(
                    connected = backend.connected,
                    currentTrack = playable.track.toSummary(),
                    isPlaying = true,
                    durationMs = playable.track.durationMs,
                    repeatMode = mutableState.value.repeatMode,
                    shuffleEnabled = mutableState.value.shuffleEnabled,
                )
                return
            } catch (_: Exception) {
                // Try the next already-sanitized location. Never surface URI-bearing exceptions.
            }
        }

        mutableState.value = mutableState.value.copy(
            currentTrack = playable.track.toSummary(),
            isPlaying = false,
            durationMs = playable.track.durationMs,
            errorMessage = "无法播放，请检查文件或网络后重试",
        )
    }

    private suspend fun seek(requestedPositionMs: Long) {
        val duration = mutableState.value.durationMs
        val position = requestedPositionMs.coerceAtLeast(0).let { value ->
            if (duration == null) value else value.coerceAtMost(duration)
        }
        backend.seekTo(position)
        queueStateWriter()?.updatePlaybackState(positionMs = position)
        mutableState.value = mutableState.value.copy(positionMs = position)
    }

    private fun queueStateWriter(): PlaybackQueueStateWriter? = queueRepository as? PlaybackQueueStateWriter

    private fun onBackendState(snapshot: BackendPlaybackSnapshot) {
        val previous = mutableState.value
        val currentTrack = snapshot.mediaId?.takeIf { it.isNotBlank() }?.let { mediaId ->
            val previousTrack = previous.currentTrack?.takeIf { it.id.value == mediaId }
            previousTrack ?: TrackSummary(
                id = com.cleartune.core.model.TrackId(mediaId),
                title = snapshot.title?.takeIf { it.isNotBlank() } ?: "未知曲目",
                durationMs = snapshot.durationMs,
            )
        }
        mutableState.value = previous.copy(
            connected = snapshot.connected,
            currentTrack = currentTrack,
            isPlaying = snapshot.isPlaying,
            positionMs = snapshot.positionMs.coerceAtLeast(0),
            durationMs = snapshot.durationMs,
            repeatMode = snapshot.repeatMode,
            shuffleEnabled = snapshot.shuffleEnabled,
            errorMessage = snapshot.errorMessage,
        )
    }

    private fun Track.toSummary() = TrackSummary(
        id = id,
        title = title,
        artworkRef = artworkRef,
        durationMs = durationMs,
    )
}
