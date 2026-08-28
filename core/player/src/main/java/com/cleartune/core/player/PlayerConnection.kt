package com.cleartune.core.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.cleartune.core.model.PlaybackMode
import com.cleartune.core.model.Song
import com.google.common.util.concurrent.ListenableFuture
import java.net.URI as JavaUri
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

@OptIn(markerClass = [UnstableApi::class])
class PlayerConnection(context: Context) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { command -> handler.post(command) }
    private val controllerFuture: ListenableFuture<MediaController>
    private var controller: MediaController? = null
    private var songMap: Map<String, Song> = emptyMap()
    private var mode: PlaybackMode = PlaybackMode.SEQUENTIAL
    private var pendingQueue: PendingQueue? = null
    private val queueUndoSnapshots = LinkedHashMap<Long, QueueUndoSnapshot>()
    private var nextQueueUndoToken = 1L

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
                        .setExtras(
                            ReplayGainMetadata.extras(song, replayGainQueue).apply {
                                SessionSongMetadata.write(this, song)
                            },
                        )
                        .build(),
                )
                .apply { playbackCacheKey(song.id, stream)?.let(::setCustomCacheKey) }
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
    fun playAt(index: Int) {
        controller?.let { player ->
            if (index !in 0 until player.mediaItemCount) return
            player.seekToDefaultPosition(index)
            player.play()
        }
    }
    fun next() = controller?.seekToNextMediaItem() ?: Unit
    fun previous() = controller?.seekToPreviousMediaItem() ?: Unit
    fun remove(index: Int) = controller?.removeMediaItem(index) ?: Unit
    fun move(from: Int, to: Int) = controller?.moveMediaItem(from, to) ?: Unit
    fun clear() = controller?.clearMediaItems() ?: Unit

    fun removeUndoable(index: Int): Long? {
        val player = controller ?: return null
        if (index !in 0 until player.mediaItemCount) return null
        val token = captureQueueUndoSnapshot(player)
        player.removeMediaItem(index)
        return token
    }

    fun clearUndoable(): Long? {
        val player = controller ?: return null
        if (player.mediaItemCount == 0) return null
        val token = captureQueueUndoSnapshot(player)
        player.clearMediaItems()
        return token
    }

    fun undoQueueMutation(token: Long) {
        val snapshot = queueUndoSnapshots.remove(token) ?: return
        controller?.apply {
            setMediaItems(
                snapshot.items,
                snapshot.currentIndex.coerceIn(snapshot.items.indices),
                snapshot.positionMs,
            )
            prepare()
            playWhenReady = snapshot.playWhenReady
        }
    }

    fun discardQueueUndo(token: Long) {
        queueUndoSnapshots.remove(token)
    }

    fun hasActiveQueue(): Boolean = (controller?.mediaItemCount ?: 0) > 0

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
                    .setExtras(
                        ReplayGainMetadata.extras(song, replayGainQueue).apply {
                            SessionSongMetadata.write(this, song)
                        },
                    )
                    .build(),
            )
            .apply { playbackCacheKey(song.id, streamUrl)?.let(::setCustomCacheKey) }
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
        val queue = (0 until player.mediaItemCount).map { index ->
            val item = player.getMediaItemAt(index)
            songMap[item.mediaId] ?: SessionSongMetadata.read(item)
        }
        songMap = queue.associateBy(Song::id)
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

    private fun captureQueueUndoSnapshot(player: Player): Long {
        if (queueUndoSnapshots.size >= MAX_QUEUE_UNDO_SNAPSHOTS) {
            queueUndoSnapshots.keys.firstOrNull()?.let(queueUndoSnapshots::remove)
        }
        val token = nextQueueUndoToken++
        queueUndoSnapshots[token] = QueueUndoSnapshot(
            items = (0 until player.mediaItemCount).map(player::getMediaItemAt),
            currentIndex = player.currentMediaItemIndex.coerceAtLeast(0),
            positionMs = player.currentPosition.coerceAtLeast(0),
            playWhenReady = player.playWhenReady,
        )
        return token
    }

    private data class PendingQueue(
        val songs: List<Song>,
        val startIndex: Int,
        val streamUrls: Map<String, String>,
        val artworkUrls: Map<String, String>,
        val positionMs: Long,
        val playWhenReady: Boolean,
    )

    private data class QueueUndoSnapshot(
        val items: List<MediaItem>,
        val currentIndex: Int,
        val positionMs: Long,
        val playWhenReady: Boolean,
    )

    private companion object {
        const val MAX_QUEUE_UNDO_SNAPSHOTS = 4
    }
}

private object SessionSongMetadata {
    private const val PREFIX = "com.cleartune.song."
    private const val ARTIST_ID = "${PREFIX}artist_id"
    private const val ALBUM_ID = "${PREFIX}album_id"
    private const val DURATION_SECONDS = "${PREFIX}duration_seconds"
    private const val TRACK_NUMBER = "${PREFIX}track_number"
    private const val DISC_NUMBER = "${PREFIX}disc_number"
    private const val YEAR = "${PREFIX}year"
    private const val GENRE = "${PREFIX}genre"
    private const val COVER_ART_ID = "${PREFIX}cover_art_id"
    private const val CONTENT_TYPE = "${PREFIX}content_type"
    private const val SUFFIX = "${PREFIX}suffix"
    private const val BIT_RATE = "${PREFIX}bit_rate"
    private const val SIZE_BYTES = "${PREFIX}size_bytes"
    private const val PLAY_COUNT = "${PREFIX}play_count"
    private const val LAST_PLAYED_AT = "${PREFIX}last_played_at"
    private const val STARRED_AT = "${PREFIX}starred_at"
    private const val CREATED_AT = "${PREFIX}created_at"

    fun write(extras: Bundle, song: Song) = extras.apply {
        song.artistId?.let { putString(ARTIST_ID, it) }
        song.albumId?.let { putString(ALBUM_ID, it) }
        putLong(DURATION_SECONDS, song.durationSeconds)
        song.trackNumber?.let { putInt(TRACK_NUMBER, it) }
        song.discNumber?.let { putInt(DISC_NUMBER, it) }
        song.year?.let { putInt(YEAR, it) }
        song.genre?.let { putString(GENRE, it) }
        song.coverArtId?.let { putString(COVER_ART_ID, it) }
        song.contentType?.let { putString(CONTENT_TYPE, it) }
        song.suffix?.let { putString(SUFFIX, it) }
        song.bitRate?.let { putInt(BIT_RATE, it) }
        song.sizeBytes?.let { putLong(SIZE_BYTES, it) }
        putLong(PLAY_COUNT, song.playCount)
        song.lastPlayedAt?.let { putLong(LAST_PLAYED_AT, it) }
        song.starredAt?.let { putLong(STARRED_AT, it) }
        song.createdAt?.let { putLong(CREATED_AT, it) }
    }

    fun read(item: MediaItem): Song {
        val metadata = item.mediaMetadata
        val extras = metadata.extras
        return Song(
            id = item.mediaId,
            title = metadata.title?.toString().orEmpty().ifBlank { item.mediaId },
            artistId = extras?.getString(ARTIST_ID),
            artistName = metadata.artist?.toString().orEmpty().ifBlank { "未知艺术家" },
            albumId = extras?.getString(ALBUM_ID),
            albumName = metadata.albumTitle?.toString().orEmpty().ifBlank { "未知专辑" },
            durationSeconds = extras?.getLong(DURATION_SECONDS, 0) ?: 0,
            trackNumber = extras.optionalInt(TRACK_NUMBER),
            discNumber = extras.optionalInt(DISC_NUMBER),
            year = extras.optionalInt(YEAR),
            genre = extras?.getString(GENRE),
            coverArtId = extras?.getString(COVER_ART_ID),
            contentType = extras?.getString(CONTENT_TYPE),
            suffix = extras?.getString(SUFFIX),
            bitRate = extras.optionalInt(BIT_RATE),
            sizeBytes = extras.optionalLong(SIZE_BYTES),
            playCount = extras?.getLong(PLAY_COUNT, 0) ?: 0,
            lastPlayedAt = extras.optionalLong(LAST_PLAYED_AT),
            starredAt = extras.optionalLong(STARRED_AT),
            createdAt = extras.optionalLong(CREATED_AT),
            replayGain = ReplayGainMetadata.values(extras).replayGain,
        )
    }

    private fun Bundle?.optionalInt(key: String): Int? =
        this?.takeIf { it.containsKey(key) }?.getInt(key)

    private fun Bundle?.optionalLong(key: String): Long? =
        this?.takeIf { it.containsKey(key) }?.getLong(key)
}

/** Stable across short-lived authentication tokens, but distinct per server and transcode quality. */
internal fun playbackCacheKey(songId: String, streamUrl: String): String? {
    val uri = runCatching { JavaUri(streamUrl) }.getOrNull() ?: return null
    if (!uri.scheme.equals("http", true) && !uri.scheme.equals("https", true)) return null
    val endpoint = buildString {
        append(uri.scheme?.lowercase())
        append("://")
        append(uri.host?.lowercase().orEmpty())
        if (uri.port >= 0) append(":${uri.port}")
        append(uri.rawPath.orEmpty())
    }
    val parameters = uri.rawQuery.orEmpty()
        .split('&')
        .mapNotNull { part ->
            val name = part.substringBefore('=', missingDelimiterValue = part)
            val value = part.substringAfter('=', missingDelimiterValue = "")
            name.takeIf(String::isNotEmpty)?.let { it to value }
        }
        .toMap()
    val bitRate = parameters["maxBitRate"] ?: "original"
    val format = parameters["format"] ?: "default"
    return "cleartune:$endpoint:song=$songId:maxBitRate=$bitRate:format=$format"
}
