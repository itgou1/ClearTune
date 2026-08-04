package com.cleartune.playback

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.cleartune.core.model.RepeatMode
import com.cleartune.core.model.Track
import com.cleartune.core.model.TrackLocation
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class Media3PlaybackBackend(context: Context) : PlaybackBackend, ObservablePlaybackBackend, QueuePlaybackBackend {
    private val appContext = context.applicationContext
    private val directExecutor = Executor(Runnable::run)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val progressUpdate = object : Runnable {
        override fun run() {
            if (!controllerFuture.isDone) return
            runCatching { controllerFuture.get() }.getOrNull()?.let(::emitSnapshot)
        }
    }
    private val controllerFuture = MediaController.Builder(
        appContext,
        SessionToken(appContext, ComponentName(appContext, ClearTunePlaybackService::class.java)),
    ).buildAsync()
    private var observer: ((BackendPlaybackSnapshot) -> Unit)? = null

    init {
        controllerFuture.addListener(
            {
                runCatching { controllerFuture.get() }.getOrNull()?.let { controller ->
                    controller.addListener(object : Player.Listener {
                        override fun onEvents(player: Player, events: Player.Events) {
                            emitSnapshot(player)
                        }
                    })
                    emitSnapshot(controller)
                }
            },
            directExecutor,
        )
    }

    override val connected: Boolean
        get() = controllerFuture.isDone && !controllerFuture.isCancelled

    override suspend fun load(track: Track, location: TrackLocation) {
        val descriptor = SecureMediaDescriptorFactory.create(track, location)
        controller().apply {
            setMediaItem(MediaItemFactory.create(descriptor))
            prepare()
        }
    }

    override suspend fun loadQueue(entries: List<ResolvedQueueEntry>, startIndex: Int, positionMs: Long) {
        require(entries.isNotEmpty())
        controller().apply {
            setMediaItems(
                entries.map { entry ->
                    MediaItemFactory.create(SecureMediaDescriptorFactory.create(entry.track, entry.location))
                },
                startIndex.coerceIn(entries.indices),
                positionMs.coerceAtLeast(0),
            )
            prepare()
        }
    }

    override suspend fun play() { controller().play() }
    override suspend fun pause() { controller().pause() }
    override suspend fun next() { controller().seekToNextMediaItem() }
    override suspend fun previous() { controller().seekToPreviousMediaItem() }
    override suspend fun seekTo(positionMs: Long) { controller().seekTo(positionMs) }

    override suspend fun setRepeat(mode: RepeatMode) {
        controller().repeatMode = when (mode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
    }

    override suspend fun setShuffle(enabled: Boolean) {
        controller().shuffleModeEnabled = enabled
    }

    fun release() {
        mainHandler.removeCallbacks(progressUpdate)
        MediaController.releaseFuture(controllerFuture)
    }

    override fun setPlaybackObserver(observer: (BackendPlaybackSnapshot) -> Unit) {
        this.observer = observer
        if (controllerFuture.isDone) runCatching { controllerFuture.get() }.getOrNull()?.let(::emitSnapshot)
    }

    private fun emitSnapshot(player: Player) {
        val duration = player.duration.takeUnless { it == androidx.media3.common.C.TIME_UNSET || it < 0 }
        observer?.invoke(
            BackendPlaybackSnapshot(
                connected = true,
                mediaId = player.currentMediaItem?.mediaId,
                title = player.currentMediaItem?.mediaMetadata?.title?.toString(),
                isPlaying = player.isPlaying,
                positionMs = player.currentPosition.coerceAtLeast(0),
                durationMs = duration,
                repeatMode = when (player.repeatMode) {
                    Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                    Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                    else -> RepeatMode.OFF
                },
                shuffleEnabled = player.shuffleModeEnabled,
                errorMessage = player.playerError?.let { "播放失败，请检查文件或网络后重试" },
            ),
        )
        mainHandler.removeCallbacks(progressUpdate)
        if (player.isPlaying) mainHandler.postDelayed(progressUpdate, 1_000)
    }

    private suspend fun controller(): MediaController = suspendCancellableCoroutine { continuation ->
        controllerFuture.addListener(
            {
                try {
                    continuation.resume(controllerFuture.get())
                } catch (error: Exception) {
                    continuation.resumeWithException(error)
                }
            },
            directExecutor,
        )
    }
}
