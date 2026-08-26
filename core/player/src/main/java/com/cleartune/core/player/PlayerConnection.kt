package com.cleartune.core.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.cleartune.core.model.PlaybackMode
import com.cleartune.core.model.Song
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PlaybackStatus { IDLE, BUFFERING, READY, PLAYING, PAUSED, ENDED, ERROR }

data class PlayerUiState(
    val connected: Boolean = false,
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val currentSong: Song? = null,
    val queue: List<Song> = emptyList(),
    val currentIndex: Int = -1,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedPositionMs: Long = 0,
    val mode: PlaybackMode = PlaybackMode.SEQUENTIAL,
    val errorMessage: String? = null,
)

class PlayerConnection(context: Context) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { command -> handler.post(command) }
    private val controllerFuture: ListenableFuture<MediaController>
    private var controller: MediaController? = null
    private var songMap: Map<String, Song> = emptyMap()
    private var mode: PlaybackMode = PlaybackMode.SEQUENTIAL
    private var pendingQueue: PendingQueue? = null

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish(player)

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            _state.value = _state.value.copy(
                status = PlaybackStatus.ERROR,
                errorMessage = "播放失败，请检查网络或音频格式",
            )
        }
    }

    private val progressTicker = object : Runnable {
        override fun run() {
            controller?.let(::publish)
            handler.postDelayed(this, 500)
        }
    }

    init {
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(appContext, token).buildAsync()
        controllerFuture.addListener(
            {
                runCatching { controllerFuture.get() }.onSuccess { mediaController ->
                    controller = mediaController
                    mediaController.addListener(listener)
                    applyMode(mediaController, mode)
                    pendingQueue?.let { pending ->
                        pendingQueue = null
                        setQueue(
                            pending.songs,
                            pending.startIndex,
                            pending.streamUrls,
                            pending.artworkUrls,
                            pending.positionMs,
                            pending.playWhenReady,
                        )
                    }
                    publish(mediaController)
                    handler.post(progressTicker)
                }.onFailure {
                    _state.value = _state.value.copy(
                        status = PlaybackStatus.ERROR,
                        errorMessage = "无法连接后台播放服务",
                    )
                }
            },
            mainExecutor,
        )
    }

    fun setQueue(
        songs: List<Song>,
        startIndex: Int,
        streamUrls: Map<String, String>,
        artworkUrls: Map<String, String> = emptyMap(),
        positionMs: Long = 0,
        playWhenReady: Boolean = true,
    ) {
        if (songs.isEmpty()) return
        if (controller == null) {
            pendingQueue = PendingQueue(
                songs,
                startIndex,
                streamUrls,
                artworkUrls,
                positionMs,
                playWhenReady,
            )
            return
        }
        songMap = songs.associateBy(Song::id)
        val replayGainQueue = ReplayGainMetadata.queueValues(songs)
        val items = songs.mapNotNull { song ->
            val stream = streamUrls[song.id] ?: return@mapNotNull null
            MediaItem.Builder()
                .setMediaId(song.id)
                .setUri(stream)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(song.title)
                        .setArtist(song.artistName)
                        .setAlbumTitle(song.albumName)
                        .setArtworkUri(artworkUrls[song.id]?.let(Uri::parse))
                        .setExtras(ReplayGainMetadata.extras(song, replayGainQueue))
                        .build(),
                )
                .build()
        }
        if (items.isEmpty()) return
        controller?.apply {
            setMediaItems(items, startIndex.coerceIn(items.indices), positionMs.coerceAtLeast(0))
            prepare()
            if (playWhenReady) play()
        }
    }

    fun togglePlayPause() {
        controller?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    fun seekTo(positionMs: Long) = controller?.seekTo(positionMs.coerceAtLeast(0)) ?: Unit
    fun next() = controller?.seekToNextMediaItem() ?: Unit
    fun previous() = controller?.seekToPreviousMediaItem() ?: Unit
    fun remove(index: Int) = controller?.removeMediaItem(index) ?: Unit
    fun move(from: Int, to: Int) = controller?.moveMediaItem(from, to) ?: Unit
    fun clear() = controller?.clearMediaItems() ?: Unit

    fun playNext(song: Song, streamUrl: String, artworkUrl: String? = null) {
        val mediaController = controller ?: return
        songMap = songMap + (song.id to song)
        val replayGainQueue = ReplayGainMetadata.queueValues(listOf(song))
        val item = MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(streamUrl)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artistName)
                    .setAlbumTitle(song.albumName)
                    .setArtworkUri(artworkUrl?.let(Uri::parse))
                    .setExtras(ReplayGainMetadata.extras(song, replayGainQueue))
                    .build(),
            )
            .build()
        val insertAt = if (mediaController.currentMediaItemIndex in 0 until mediaController.mediaItemCount) {
            mediaController.currentMediaItemIndex + 1
        } else {
            mediaController.mediaItemCount
        }
        mediaController.addMediaItem(insertAt, item)
    }

    fun cycleMode(): PlaybackMode {
        mode = QueuePlanner.nextMode(mode)
        controller?.let { applyMode(it, mode) }
        _state.value = _state.value.copy(mode = mode)
        return mode
    }

    fun setMode(value: PlaybackMode) {
        mode = value
        controller?.let { applyMode(it, value) }
        _state.value = _state.value.copy(mode = value)
    }

    fun release() {
        handler.removeCallbacks(progressTicker)
        controller?.removeListener(listener)
        MediaController.releaseFuture(controllerFuture)
        controller = null
    }

    private fun applyMode(player: Player, value: PlaybackMode) {
        player.shuffleModeEnabled = value == PlaybackMode.SHUFFLE
        player.repeatMode = when (value) {
            PlaybackMode.SEQUENTIAL -> Player.REPEAT_MODE_OFF
            PlaybackMode.REPEAT_ALL, PlaybackMode.SHUFFLE -> Player.REPEAT_MODE_ALL
            PlaybackMode.REPEAT_ONE -> Player.REPEAT_MODE_ONE
        }
    }

    private fun publish(player: Player) {
        val queue = (0 until player.mediaItemCount).mapNotNull { index ->
            songMap[player.getMediaItemAt(index).mediaId]
        }
        val index = player.currentMediaItemIndex.takeIf { it in queue.indices } ?: -1
        val status = when {
            player.playerError != null -> PlaybackStatus.ERROR
            player.playbackState == Player.STATE_BUFFERING -> PlaybackStatus.BUFFERING
            player.playbackState == Player.STATE_ENDED -> PlaybackStatus.ENDED
            player.isPlaying -> PlaybackStatus.PLAYING
            player.playbackState == Player.STATE_READY && player.playWhenReady -> PlaybackStatus.READY
            player.playbackState == Player.STATE_READY -> PlaybackStatus.PAUSED
            else -> PlaybackStatus.IDLE
        }
        _state.value = PlayerUiState(
            connected = true,
            status = status,
            currentSong = queue.getOrNull(index),
            queue = queue,
            currentIndex = index,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.takeIf { it > 0 } ?: 0,
            bufferedPositionMs = player.bufferedPosition.coerceAtLeast(0),
            mode = mode,
            errorMessage = player.playerError?.let { "播放失败，请稍后重试" },
        )
    }

    private data class PendingQueue(
        val songs: List<Song>,
        val startIndex: Int,
        val streamUrls: Map<String, String>,
        val artworkUrls: Map<String, String>,
        val positionMs: Long,
        val playWhenReady: Boolean,
    )
}
