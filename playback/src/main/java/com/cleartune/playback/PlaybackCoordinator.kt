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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

data class ResolvedQueueEntry(
    val track: Track,
    val location: TrackLocation,
    val occurrenceId: String = track.id.value,
)

interface QueuePlaybackBackend {
    suspend fun loadQueue(entries: List<ResolvedQueueEntry>, startIndex: Int, positionMs: Long)
    suspend fun replaceCurrent(entry: ResolvedQueueEntry, positionMs: Long) = Unit
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
    val occurrenceId: String? = mediaId,
    val playWhenReady: Boolean = isPlaying,
    val errorMessage: String? = null,
    val failure: BackendPlaybackFailure? = null,
)

sealed interface BackendPlaybackFailure {
    val message: String
    data class Item(
        override val message: String,
        val occurrenceId: String? = null,
    ) : BackendPlaybackFailure
    data class Global(override val message: String) : BackendPlaybackFailure
}

interface ObservablePlaybackBackend {
    fun setPlaybackObserver(observer: (BackendPlaybackSnapshot) -> Unit)
}

class PlaybackCoordinator(
    private val libraryRepository: PlaybackLibraryRepository,
    private val queueRepository: QueueRepository,
    private val backend: PlaybackBackend,
    private val environment: PlaybackEnvironment,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : PlaybackGateway {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(PlaybackState(connected = backend.connected))
    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()
    private var activePlayback: ActivePlayback? = null
    @Volatile private var activeQueueEntries: Map<String, ActivePlayback> = emptyMap()
    @Volatile private var activeOccurrenceId: String? = null
    @Volatile private var lastBackendPositionMs: Long? = null
    @Volatile private var lastBackendPlayWhenReady: Boolean? = null
    private var lastHandledFailure: BackendPlaybackFailure? = null
    private var snapshotSequence = 0L
    private var latestPersistedSnapshot = 0L

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
                PlaybackCommand.Next -> moveToAdjacentOccurrence(next = true)
                PlaybackCommand.Previous -> moveToAdjacentOccurrence(next = false)
                is PlaybackCommand.SeekTo -> seek(command.positionMs)
                is PlaybackCommand.SetRepeat -> {
                    backend.setRepeat(command.mode)
                    queueStateWriter()?.updatePlaybackState(repeatMode = command.mode)
                    mutableState.value = mutableState.value.copy(repeatMode = command.mode)
                }
                is PlaybackCommand.SetShuffle -> {
                    reloadQueueForShuffleLocked(command.enabled)
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
        queueStateWriter()?.updatePlaybackState(playWhenReady = false)
        backend.pause()
        val resolved = linkedMapOf<com.cleartune.core.model.QueueItemId, ResolvedQueueEntry>()
        val playbackEntries = linkedMapOf<String, ActivePlayback>()
        queue.items.forEach { item ->
            val playable = libraryRepository.getPlayableTrack(item.trackId) ?: return@forEach
            val resolution = LocationResolver.resolve(
                playableTrack = playable,
                fileExists = environment.fileExists,
                uriReadable = environment.uriReadable,
                networkAvailable = environment.networkAvailable(),
            )
            val attempts = (resolution as? LocationResolution.Ready)?.attempts
                ?.takeIf { it.isNotEmpty() }
                ?: return@forEach
            resolved[item.id] = ResolvedQueueEntry(playable.track, attempts.first(), item.id.value)
            playbackEntries[item.id.value] = ActivePlayback(
                occurrenceId = item.id.value,
                track = playable.track,
                attempts = attempts,
                attemptIndex = 0,
            )
        }
        if (resolved.isEmpty()) return false
        val recovery = (queueRepository as? PlaybackQueueRecoveryProvider)?.recoveryState()
        val occurrenceOrder = if (queue.shuffleEnabled) {
            recovery?.shuffleOrder.orEmpty()
        } else {
            queue.items.map { it.id }
        }
        val orderedIds = occurrenceOrder.filter { it in resolved } + resolved.keys.filterNot { it in occurrenceOrder }
        val entries = orderedIds.mapNotNull(resolved::get)
        val currentOccurrence = queue.items.getOrNull(queue.currentIndex)?.id
        activeQueueEntries = playbackEntries
        activeOccurrenceId = currentOccurrence?.value
        activePlayback = currentOccurrence?.value?.let(playbackEntries::get)
        val startIndex = orderedIds.indexOf(currentOccurrence).takeIf { it >= 0 } ?: 0
        queueBackend.loadQueue(entries, startIndex, queue.positionMs)
        backend.setRepeat(queue.repeatMode)
        backend.setShuffle(queue.shuffleEnabled)
        return true
    }

    private suspend fun reloadQueueForShuffleLocked(enabled: Boolean) {
        val writer = queueStateWriter()
        val priorQueue = queueRepository.observeQueue().first()
        val currentOccurrence = activeOccurrenceId
            ?: priorQueue.items.getOrNull(priorQueue.currentIndex)?.id?.value
        val positionMs = lastBackendPositionMs ?: priorQueue.positionMs
        val playWhenReady = lastBackendPlayWhenReady ?: priorQueue.playWhenReady
        writer?.updatePlaybackState(
            positionMs = positionMs,
            playWhenReady = playWhenReady,
            shuffleEnabled = enabled,
        )
        val queue = queueRepository.observeQueue().first()
        val queueBackend = backend as? QueuePlaybackBackend
        if (queueBackend != null) {
            val resolved = linkedMapOf<com.cleartune.core.model.QueueItemId, ResolvedQueueEntry>()
            val playbackEntries = linkedMapOf<String, ActivePlayback>()
            queue.items.forEach { item ->
                val playable = libraryRepository.getPlayableTrack(item.trackId) ?: return@forEach
                val attempts = (LocationResolver.resolve(
                    playableTrack = playable,
                    fileExists = environment.fileExists,
                    uriReadable = environment.uriReadable,
                    networkAvailable = environment.networkAvailable(),
                ) as? LocationResolution.Ready)?.attempts.orEmpty()
                if (attempts.isEmpty()) return@forEach
                resolved[item.id] = ResolvedQueueEntry(playable.track, attempts.first(), item.id.value)
                playbackEntries[item.id.value] = ActivePlayback(
                    occurrenceId = item.id.value,
                    track = playable.track,
                    attempts = attempts,
                    attemptIndex = 0,
                )
            }
            if (resolved.isNotEmpty()) {
                val recovery = (queueRepository as? PlaybackQueueRecoveryProvider)?.recoveryState()
                val persistedOrder = if (enabled) recovery?.shuffleOrder.orEmpty() else queue.items.map { it.id }
                val orderedIds = persistedOrder.filter { it in resolved } +
                    resolved.keys.filterNot { it in persistedOrder }
                val entries = orderedIds.mapNotNull(resolved::get)
                val startIndex = orderedIds.indexOfFirst { it.value == currentOccurrence }
                    .takeIf { it >= 0 } ?: 0
                activeQueueEntries = playbackEntries
                activeOccurrenceId = orderedIds[startIndex].value
                activePlayback = playbackEntries[activeOccurrenceId]
                queueBackend.loadQueue(entries, startIndex, positionMs)
            }
        }
        backend.setShuffle(enabled)
        if (playWhenReady) backend.play() else backend.pause()
        val currentIndex = currentOccurrence?.let { occurrenceId ->
            queue.items.indexOfFirst { it.id.value == occurrenceId }.takeIf { it >= 0 }
        }
        writer?.updatePlaybackState(
            currentIndex = currentIndex,
            positionMs = positionMs,
            playWhenReady = playWhenReady,
            shuffleEnabled = enabled,
        )
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
                val occurrenceId = queueRepository.observeQueue().first()
                    .items.firstOrNull()?.id?.value ?: command.trackId.value
                activePlayback = ActivePlayback(occurrenceId, playable.track, attempts, attemptIndex)
                activeOccurrenceId = occurrenceId
                if (backend is QueuePlaybackBackend && attemptIndex == 0) {
                    if (!syncQueueLocked()) backend.load(playable.track, location)
                } else {
                    backend.load(playable.track, location)
                }
                backend.play()
                queueStateWriter()?.updatePlaybackState(playWhenReady = true)
                mutableState.value = PlaybackState(
                    connected = backend.connected,
                    currentTrack = playable.track.toSummary(),
                    isPlaying = true,
                    durationMs = playable.track.durationMs,
                    repeatMode = mutableState.value.repeatMode,
                    shuffleEnabled = mutableState.value.shuffleEnabled,
                )
                return
            } catch (error: CancellationException) {
                throw error
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

    private suspend fun moveToAdjacentOccurrence(next: Boolean) {
        val queue = queueRepository.observeQueue().first()
        if (next) backend.next() else backend.previous()
        if (queue.items.isEmpty()) return
        val recovery = (queueRepository as? PlaybackQueueRecoveryProvider)?.recoveryState()
        val order = if (queue.shuffleEnabled) {
            recovery?.shuffleOrder.orEmpty().ifEmpty { queue.items.map { it.id } }
        } else {
            queue.items.map { it.id }
        }
        val currentId = queue.items.getOrNull(queue.currentIndex)?.id
        val orderIndex = order.indexOf(currentId).takeIf { it >= 0 } ?: 0
        val adjacentOrderIndex = if (next) {
            if (orderIndex == order.lastIndex && queue.repeatMode == RepeatMode.ALL) 0
            else (orderIndex + 1).coerceAtMost(order.lastIndex)
        } else {
            if (orderIndex == 0 && queue.repeatMode == RepeatMode.ALL) order.lastIndex
            else (orderIndex - 1).coerceAtLeast(0)
        }
        val adjacentId = order[adjacentOrderIndex]
        val adjacentQueueIndex = queue.items.indexOfFirst { it.id == adjacentId }
        queueStateWriter()?.updatePlaybackState(currentIndex = adjacentQueueIndex, positionMs = 0)
    }

    private fun queueStateWriter(): PlaybackQueueStateWriter? = queueRepository as? PlaybackQueueStateWriter

    private fun onBackendState(snapshot: BackendPlaybackSnapshot) {
        val previous = mutableState.value
        val itemFailure = snapshot.failure as? BackendPlaybackFailure.Item
        if (itemFailure?.occurrenceId != null && activeOccurrenceId != null &&
            itemFailure.occurrenceId != activeOccurrenceId
        ) return
        lastBackendPositionMs = snapshot.positionMs.coerceAtLeast(0)
        lastBackendPlayWhenReady = snapshot.playWhenReady
        snapshot.occurrenceId?.let { occurrenceId ->
            activeQueueEntries[occurrenceId]?.let { entry ->
                activeOccurrenceId = occurrenceId
                activePlayback = entry
            }
        }
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
            errorMessage = snapshot.failure?.message ?: snapshot.errorMessage,
        )
        val failure = snapshot.failure
        if (failure == null) {
            lastHandledFailure = null
        } else if (failure != lastHandledFailure) {
            lastHandledFailure = failure
            val sequence = ++snapshotSequence
            scope.launch { persistSnapshotAndHandleFailure(sequence, snapshot, failure) }
            return
        }
        val sequence = ++snapshotSequence
        scope.launch { persistSnapshotAndHandleFailure(sequence, snapshot, null) }
    }

    private suspend fun persistSnapshotAndHandleFailure(
        sequence: Long,
        snapshot: BackendPlaybackSnapshot,
        failure: BackendPlaybackFailure?,
    ) {
        mutex.withLock {
            if (sequence <= latestPersistedSnapshot) return@withLock
            val queue = queueRepository.observeQueue().first()
            val occurrenceIndex = snapshot.occurrenceId?.let { occurrenceId ->
                queue.items.indexOfFirst { it.id.value == occurrenceId }.takeIf { it >= 0 }
            }
            queueStateWriter()?.updatePlaybackState(
                currentIndex = occurrenceIndex,
                positionMs = snapshot.positionMs,
                playWhenReady = snapshot.playWhenReady,
                repeatMode = snapshot.repeatMode,
                shuffleEnabled = snapshot.shuffleEnabled,
            )
            latestPersistedSnapshot = sequence
            when (failure) {
                is BackendPlaybackFailure.Global -> {
                    backend.pause()
                    queueStateWriter()?.updatePlaybackState(playWhenReady = false)
                    mutableState.value = mutableState.value.copy(isPlaying = false, errorMessage = failure.message)
                }
                is BackendPlaybackFailure.Item -> retryNextLocation(failure, snapshot.playWhenReady)
                null -> Unit
            }
        }
    }

    private suspend fun retryNextLocation(failure: BackendPlaybackFailure.Item, resumePlayback: Boolean) {
        val active = activePlayback
        if (active == null) {
            backend.pause()
            mutableState.value = mutableState.value.copy(isPlaying = false, errorMessage = failure.message)
            return
        }
        for (attemptIndex in (active.attemptIndex + 1)..active.attempts.lastIndex) {
            try {
                val next = active.copy(attemptIndex = attemptIndex)
                if (backend is QueuePlaybackBackend) {
                    backend.replaceCurrent(
                        ResolvedQueueEntry(next.track, next.attempts[attemptIndex], next.occurrenceId),
                        mutableState.value.positionMs,
                    )
                } else {
                    backend.load(active.track, active.attempts[attemptIndex])
                }
                activePlayback = next
                activeQueueEntries = activeQueueEntries + (next.occurrenceId to next)
                if (resumePlayback) {
                    backend.play()
                    queueStateWriter()?.updatePlaybackState(playWhenReady = true)
                }
                mutableState.value = mutableState.value.copy(
                    isPlaying = resumePlayback,
                    errorMessage = null,
                )
                return
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Continue through the already-sanitized locations only.
            }
        }
        backend.pause()
        queueStateWriter()?.updatePlaybackState(playWhenReady = false)
        mutableState.value = mutableState.value.copy(isPlaying = false, errorMessage = failure.message)
    }

    private fun Track.toSummary() = TrackSummary(
        id = id,
        title = title,
        artworkRef = artworkRef,
        durationMs = durationMs,
        albumTitle = albumTitle,
        artistNames = artistNames,
    )

    private data class ActivePlayback(
        val occurrenceId: String,
        val track: Track,
        val attempts: List<TrackLocation>,
        val attemptIndex: Int,
    )
}
