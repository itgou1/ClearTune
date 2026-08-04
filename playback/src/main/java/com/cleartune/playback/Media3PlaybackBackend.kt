package com.cleartune.playback

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.cleartune.core.model.RepeatMode
import com.cleartune.core.model.Track
import com.cleartune.core.model.TrackLocation
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object Media3ErrorClassifier {
    fun classify(error: PlaybackException): BackendPlaybackFailure = classify(
        error.errorCode,
        error.cause,
        error.cause?.causeChain()
            ?.filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            ?.firstOrNull()
            ?.responseCode,
    )

    fun classify(errorCode: Int, cause: Throwable?, httpStatus: Int? = null): BackendPlaybackFailure {
        cause?.causeChain()?.filterIsInstance<CancellationException>()?.firstOrNull()?.let { throw it }
        if (errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS && httpStatus in ITEM_HTTP_STATUSES) {
            return BackendPlaybackFailure.Item("This copy could not be played")
        }
        return when (errorCode) {
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            -> BackendPlaybackFailure.Item("This copy could not be played")
            else -> BackendPlaybackFailure.Global(
                "Playback paused because the connection or account is unavailable",
            )
        }
    }

    private fun Throwable.causeChain(): Sequence<Throwable> = generateSequence(this) { it.cause }
    private val ITEM_HTTP_STATUSES = setOf(404, 410)
}

internal interface ControllerDispatcher<T : Any> {
    suspend fun <R> execute(controller: T, command: (T) -> R): R
    fun release(controller: T)
}

internal class ControllerLifecycle<T : Any>(
    private val connect: suspend () -> T,
    private val dispatcher: ControllerDispatcher<T>,
    private val onConnectionChanged: (Boolean) -> Unit = {},
) {
    private val commandMutex = Mutex()
    private val stateLock = Any()
    private val invalidControllers = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
    private val releasedControllers = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
    private var generation = 0L
    private var permanentlyReleased = false
    @Volatile private var current: T? = null

    val connected: Boolean get() = current != null
    fun currentOrNull(): T? = current

    suspend fun <R> execute(command: (T) -> R): R = commandMutex.withLock {
        val controller = current ?: acquireController()
        try {
            dispatcher.execute(controller, command)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            invalidate(controller)
            throw error
        }
    }

    fun disconnect(controller: T) {
        val wasCurrent = synchronized(stateLock) {
            invalidControllers += controller
            if (current !== controller) {
                false
            } else {
                current = null
                generation++
                true
            }
        }
        releaseOnce(controller)
        if (wasCurrent) onConnectionChanged(false)
    }

    fun release() {
        val released = synchronized(stateLock) {
            if (permanentlyReleased) return
            permanentlyReleased = true
            generation++
            current.also { current = null }
        }
        released?.let(::releaseOnce)
        if (released != null) onConnectionChanged(false)
    }

    private suspend fun acquireController(): T {
        val expectedGeneration = synchronized(stateLock) {
            check(!permanentlyReleased) { "Controller lifecycle has been released" }
            generation
        }
        val connectedController = connect()
        val accepted = synchronized(stateLock) {
            if (
                permanentlyReleased ||
                generation != expectedGeneration ||
                connectedController in invalidControllers
            ) {
                false
            } else {
                current = connectedController
                true
            }
        }
        if (!accepted) {
            releaseOnce(connectedController)
            error("Controller connection was invalidated before assignment")
        }
        onConnectionChanged(true)
        return connectedController
    }

    private fun invalidate(controller: T) {
        val wasCurrent = synchronized(stateLock) {
            invalidControllers += controller
            if (current !== controller) {
                false
            } else {
                current = null
                generation++
                true
            }
        }
        releaseOnce(controller)
        if (wasCurrent) onConnectionChanged(false)
    }

    private fun releaseOnce(controller: T) {
        val shouldRelease = synchronized(stateLock) { releasedControllers.add(controller) }
        if (shouldRelease) dispatcher.release(controller)
    }
}

private class MediaControllerDispatcher : ControllerDispatcher<MediaController> {
    override suspend fun <R> execute(
        controller: MediaController,
        command: (MediaController) -> R,
    ): R {
        if (Looper.myLooper() == controller.applicationLooper) return command(controller)
        return suspendCancellableCoroutine { continuation ->
            val posted = Handler(controller.applicationLooper).post {
                if (!continuation.isActive) return@post
                try {
                    continuation.resume(command(controller))
                } catch (error: Throwable) {
                    continuation.resumeWithException(error)
                }
            }
            if (!posted && continuation.isActive) {
                continuation.resumeWithException(IllegalStateException("Controller looper is unavailable"))
            }
        }
    }

    override fun release(controller: MediaController) {
        if (Looper.myLooper() == controller.applicationLooper) {
            controller.release()
        } else {
            Handler(controller.applicationLooper).post(controller::release)
        }
    }
}

class Media3PlaybackBackend(context: Context) : PlaybackBackend, ObservablePlaybackBackend, QueuePlaybackBackend {
    private val appContext = context.applicationContext
    private val directExecutor = Executor(Runnable::run)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val connectionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: ((BackendPlaybackSnapshot) -> Unit)? = null
    private var lastSnapshot: BackendPlaybackSnapshot? = null
    private val configuredControllers = Collections.newSetFromMap(
        IdentityHashMap<MediaController, Boolean>(),
    )
    @Volatile private var logicalShuffleEnabled = false
    @Volatile private var occurrenceTrackIds: Map<String, String> = emptyMap()
    private val controllerLifecycle = ControllerLifecycle(
        connect = ::connectController,
        dispatcher = MediaControllerDispatcher(),
        onConnectionChanged = { isConnected -> if (!isConnected) emitDisconnected() },
    )
    private val progressUpdate = object : Runnable {
        override fun run() {
            connectionScope.launch {
                runCatching { withController(::emitSnapshot) }
                    .onFailure { emitDisconnected() }
            }
        }
    }

    init {
        connectionScope.launch {
            runCatching { withController(::emitSnapshot) }
                .onFailure { emitDisconnected() }
        }
    }

    override val connected: Boolean
        get() = controllerLifecycle.connected

    override suspend fun load(track: Track, location: TrackLocation) {
        val descriptor = SecureMediaDescriptorFactory.create(track, location)
        withController { controller ->
            controller.playWhenReady = false
            occurrenceTrackIds = mapOf(descriptor.mediaId to descriptor.mediaId)
            PrivateMediaSourceRegistry.replaceSources(
                listOf(
                    descriptor.mediaId to PrivateMediaSource(
                        descriptor.playbackUri,
                        descriptor.sourceId,
                        descriptor.locationId,
                    ),
                ),
            )
            controller.setMediaItem(MediaItemFactory.create(descriptor))
            controller.prepare()
        }
    }

    override suspend fun loadQueue(entries: List<ResolvedQueueEntry>, startIndex: Int, positionMs: Long) {
        require(entries.isNotEmpty())
        val descriptors = entries.map { entry ->
            entry to SecureMediaDescriptorFactory.create(entry.track, entry.location)
        }
        withController { controller ->
            controller.playWhenReady = false
            occurrenceTrackIds = entries.associate { it.occurrenceId to it.track.id.value }
            PrivateMediaSourceRegistry.replaceSources(descriptors.map { (entry, descriptor) ->
                entry.occurrenceId to PrivateMediaSource(
                    descriptor.playbackUri,
                    descriptor.sourceId,
                    descriptor.locationId,
                )
            })
            controller.setMediaItems(
                descriptors.map { (entry, descriptor) ->
                    MediaItemFactory.create(descriptor, mediaId = entry.occurrenceId)
                },
                startIndex.coerceIn(entries.indices),
                positionMs.coerceAtLeast(0),
            )
            controller.prepare()
        }
    }

    override suspend fun replaceCurrent(entry: ResolvedQueueEntry, positionMs: Long) {
        val descriptor = SecureMediaDescriptorFactory.create(entry.track, entry.location)
        withController { controller ->
            val currentIndex = controller.currentMediaItemIndex.coerceAtLeast(0)
            occurrenceTrackIds = occurrenceTrackIds + (entry.occurrenceId to entry.track.id.value)
            PrivateMediaSourceRegistry.registerActive(
                entry.occurrenceId,
                PrivateMediaSource(descriptor.playbackUri, descriptor.sourceId, descriptor.locationId),
            )
            controller.replaceMediaItem(
                currentIndex,
                MediaItemFactory.create(descriptor, mediaId = entry.occurrenceId),
            )
            controller.seekTo(currentIndex, positionMs.coerceAtLeast(0))
            controller.prepare()
        }
    }

    override suspend fun play() = withController { it.play() }
    override suspend fun pause() = withController { it.pause() }
    override suspend fun next() = withController { it.seekToNextMediaItem() }
    override suspend fun previous() = withController { it.seekToPreviousMediaItem() }
    override suspend fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs) }

    override suspend fun setRepeat(mode: RepeatMode) {
        withController { controller ->
            controller.repeatMode = when (mode) {
                RepeatMode.OFF -> Player.REPEAT_MODE_OFF
                RepeatMode.ALL -> Player.REPEAT_MODE_ALL
                RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            }
        }
    }

    override suspend fun setShuffle(enabled: Boolean) {
        logicalShuffleEnabled = enabled
        withController { controller ->
            // The coordinator loads the persisted occurrence order explicitly.
            controller.shuffleModeEnabled = false
            emitSnapshot(controller)
        }
    }

    fun release() {
        mainHandler.removeCallbacks(progressUpdate)
        connectionScope.cancel()
        controllerLifecycle.release()
        PrivateMediaSourceRegistry.clear()
    }

    override fun setPlaybackObserver(observer: (BackendPlaybackSnapshot) -> Unit) {
        this.observer = observer
        lastSnapshot?.let(observer)
    }

    private suspend fun connectController(): MediaController = suspendCancellableCoroutine { continuation ->
        val future = MediaController.Builder(
            appContext,
            SessionToken(appContext, ComponentName(appContext, ClearTunePlaybackService::class.java)),
        ).setListener(
            object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    controllerLifecycle.disconnect(controller)
                }
            },
        ).buildAsync()
        future.addListener(
            {
                try {
                    val controller = future.get()
                    if (!continuation.isActive) {
                        MediaControllerDispatcher().release(controller)
                        return@addListener
                    }
                    continuation.resume(controller)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            },
            directExecutor,
        )
        continuation.invokeOnCancellation { MediaController.releaseFuture(future) }
    }

    private suspend fun <R> withController(command: (MediaController) -> R): R =
        controllerLifecycle.execute { controller ->
            if (configuredControllers.add(controller)) {
                controller.addListener(
                    object : Player.Listener {
                        override fun onEvents(player: Player, events: Player.Events) {
                            if (!events.contains(Player.EVENT_PLAYER_ERROR)) emitSnapshot(player)
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            emitSnapshot(controller, Media3ErrorClassifier.classify(error))
                        }
                    },
                )
            }
            command(controller)
        }

    private fun emitSnapshot(player: Player, failure: BackendPlaybackFailure? = null) {
        val occurrenceId = player.currentMediaItem?.mediaId
        val identifiedFailure = when (failure) {
            is BackendPlaybackFailure.Item -> failure.copy(occurrenceId = failure.occurrenceId ?: occurrenceId)
            else -> failure
        }
        val snapshot = BackendPlaybackSnapshot(
            connected = connected,
            mediaId = occurrenceId?.let(occurrenceTrackIds::get) ?: occurrenceId,
            occurrenceId = occurrenceId,
            title = player.currentMediaItem?.mediaMetadata?.title?.toString(),
            isPlaying = player.isPlaying,
            playWhenReady = player.playWhenReady,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.takeUnless { it == C.TIME_UNSET || it < 0 },
            repeatMode = when (player.repeatMode) {
                Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                else -> RepeatMode.OFF
            },
            shuffleEnabled = logicalShuffleEnabled,
            failure = identifiedFailure,
        )
        lastSnapshot = snapshot
        observer?.invoke(snapshot)
        mainHandler.removeCallbacks(progressUpdate)
        if (player.isPlaying) mainHandler.postDelayed(progressUpdate, 1_000)
    }

    private fun emitDisconnected() {
        mainHandler.removeCallbacks(progressUpdate)
        val snapshot = lastSnapshot?.copy(connected = false, isPlaying = false)
            ?: BackendPlaybackSnapshot(
                connected = false,
                mediaId = null,
                title = null,
                isPlaying = false,
                positionMs = 0,
                durationMs = null,
                repeatMode = RepeatMode.OFF,
                shuffleEnabled = logicalShuffleEnabled,
            )
        lastSnapshot = snapshot
        observer?.invoke(snapshot)
    }
}
