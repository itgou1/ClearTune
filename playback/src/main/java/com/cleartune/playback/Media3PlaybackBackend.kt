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

internal class ControllerLifecycle<T : Any>(
    private val connect: suspend () -> T,
    private val releaseController: (T) -> Unit,
    private val onConnectionChanged: (Boolean) -> Unit = {},
) {
    private val commandMutex = Mutex()
    private val stateLock = Any()
    @Volatile private var current: T? = null

    val connected: Boolean get() = current != null
    fun currentOrNull(): T? = current

    suspend fun <R> execute(command: suspend (T) -> R): R = commandMutex.withLock {
        val controller = current ?: connect().also { connectedController ->
            synchronized(stateLock) { current = connectedController }
            onConnectionChanged(true)
        }
        command(controller)
    }

    fun disconnect(controller: T) {
        val released = synchronized(stateLock) {
            if (current !== controller) null else current.also { current = null }
        } ?: return
        releaseController(released)
        onConnectionChanged(false)
    }

    fun release() {
        val released = synchronized(stateLock) { current.also { current = null } } ?: return
        releaseController(released)
        onConnectionChanged(false)
    }
}

class Media3PlaybackBackend(context: Context) : PlaybackBackend, ObservablePlaybackBackend, QueuePlaybackBackend {
    private val appContext = context.applicationContext
    private val directExecutor = Executor(Runnable::run)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val connectionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: ((BackendPlaybackSnapshot) -> Unit)? = null
    private var lastSnapshot: BackendPlaybackSnapshot? = null
    @Volatile private var logicalShuffleEnabled = false
    private val controllerLifecycle = ControllerLifecycle(
        connect = ::connectController,
        releaseController = MediaController::release,
        onConnectionChanged = { isConnected -> if (!isConnected) emitDisconnected() },
    )
    private val progressUpdate = object : Runnable {
        override fun run() {
            controllerLifecycle.currentOrNull()
                ?.takeIf { it.isConnected }
                ?.let(::emitSnapshot)
        }
    }

    init {
        connectionScope.launch {
            runCatching { controllerLifecycle.execute { emitSnapshot(it) } }
                .onFailure { emitDisconnected() }
        }
    }

    override val connected: Boolean
        get() = controllerLifecycle.currentOrNull()?.isConnected == true

    override suspend fun load(track: Track, location: TrackLocation) {
        val descriptor = SecureMediaDescriptorFactory.create(track, location)
        controllerLifecycle.execute { controller ->
            PrivateMediaSourceRegistry.replace(listOf(descriptor.mediaId to descriptor.playbackUri))
            controller.setMediaItem(MediaItemFactory.create(descriptor))
            controller.prepare()
        }
    }

    override suspend fun loadQueue(entries: List<ResolvedQueueEntry>, startIndex: Int, positionMs: Long) {
        require(entries.isNotEmpty())
        val descriptors = entries.map { entry ->
            SecureMediaDescriptorFactory.create(entry.track, entry.location)
        }
        controllerLifecycle.execute { controller ->
            PrivateMediaSourceRegistry.replace(descriptors.map { it.mediaId to it.playbackUri })
            controller.setMediaItems(
                descriptors.map(MediaItemFactory::create),
                startIndex.coerceIn(entries.indices),
                positionMs.coerceAtLeast(0),
            )
            controller.prepare()
        }
    }

    override suspend fun play() = controllerLifecycle.execute { it.play() }
    override suspend fun pause() = controllerLifecycle.execute { it.pause() }
    override suspend fun next() = controllerLifecycle.execute { it.seekToNextMediaItem() }
    override suspend fun previous() = controllerLifecycle.execute { it.seekToPreviousMediaItem() }
    override suspend fun seekTo(positionMs: Long) = controllerLifecycle.execute { it.seekTo(positionMs) }

    override suspend fun setRepeat(mode: RepeatMode) {
        controllerLifecycle.execute { controller ->
            controller.repeatMode = when (mode) {
                RepeatMode.OFF -> Player.REPEAT_MODE_OFF
                RepeatMode.ALL -> Player.REPEAT_MODE_ALL
                RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            }
        }
    }

    override suspend fun setShuffle(enabled: Boolean) {
        logicalShuffleEnabled = enabled
        controllerLifecycle.execute { controller ->
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
                        controller.release()
                        return@addListener
                    }
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
                    continuation.resume(controller)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            },
            directExecutor,
        )
        continuation.invokeOnCancellation { MediaController.releaseFuture(future) }
    }

    private fun emitSnapshot(player: Player, failure: BackendPlaybackFailure? = null) {
        val snapshot = BackendPlaybackSnapshot(
            connected = connected,
            mediaId = player.currentMediaItem?.mediaId,
            title = player.currentMediaItem?.mediaMetadata?.title?.toString(),
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.takeUnless { it == C.TIME_UNSET || it < 0 },
            repeatMode = when (player.repeatMode) {
                Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                else -> RepeatMode.OFF
            },
            shuffleEnabled = logicalShuffleEnabled,
            failure = failure,
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
