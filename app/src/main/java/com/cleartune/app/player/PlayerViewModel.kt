package com.cleartune.app.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleartune.core.datastore.PlaybackPreferences
import com.cleartune.core.model.PlaybackMode
import com.cleartune.core.model.Song
import com.cleartune.core.player.PlaybackStatus
import com.cleartune.core.player.PlayerConnection
import com.cleartune.core.player.PlayerUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val repository: PlaybackRepository,
    private val preferences: PlaybackPreferences,
) : ViewModel() {
    private val connection = PlayerConnection(context)
    val state: StateFlow<PlayerUiState> = connection.state.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        PlayerUiState(),
    )

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private var restored = false
    private var submittedSongId: String? = null

    init {
        viewModelScope.launch {
            val mode = preferences.mode.first()
            connection.setMode(mode)
            state.filter { it.connected }.first()
            repository.restoreQueue()?.let { restoredQueue ->
                repository.urls(restoredQueue.songs)?.let { urls ->
                    connection.setQueue(
                        songs = restoredQueue.songs,
                        startIndex = restoredQueue.currentIndex,
                        streamUrls = urls.streams,
                        artworkUrls = urls.artwork,
                        positionMs = restoredQueue.positionMs,
                        playWhenReady = false,
                    )
                }
            }
            repository.newerServerQueue()?.takeIf { state.value.status != PlaybackStatus.PLAYING }?.let { serverQueue ->
                repository.urls(serverQueue.songs)?.let { urls ->
                    connection.setQueue(
                        songs = serverQueue.songs,
                        startIndex = serverQueue.currentIndex,
                        streamUrls = urls.streams,
                        artworkUrls = urls.artwork,
                        positionMs = serverQueue.positionMs,
                        playWhenReady = false,
                    )
                }
            }
            restored = true
        }
        viewModelScope.launch {
            state.map { snapshot ->
                Triple(snapshot.queue.map(Song::id), snapshot.currentIndex, snapshot.status)
            }.distinctUntilChanged().debounce(750).collect {
                if (restored) {
                    repository.persistQueue(state.value)
                    repository.saveServerQueue(state.value)
                }
            }
        }
        viewModelScope.launch {
            state.filter { snapshot -> snapshot.connected && snapshot.queue.isNotEmpty() }
                .map { snapshot ->
                    LocalPlaybackCheckpoint(
                        songId = snapshot.currentSong?.id,
                        currentIndex = snapshot.currentIndex,
                        positionBucket = snapshot.positionMs / LOCAL_CHECKPOINT_INTERVAL_MS,
                    )
                }
                .distinctUntilChanged()
                .collect {
                    if (restored) repository.persistQueue(state.value)
                }
        }
        viewModelScope.launch {
            state.map { it.currentSong?.id }.distinctUntilChanged().collect { songId ->
                submittedSongId = null
                songId?.let { repository.recordStarted(it) }
            }
        }
        viewModelScope.launch {
            state.collect { snapshot ->
                val song = snapshot.currentSong ?: return@collect
                val threshold = minOf(240_000L, (snapshot.durationMs / 2).coerceAtLeast(30_000L))
                if (snapshot.positionMs >= threshold && submittedSongId != song.id) {
                    submittedSongId = song.id
                    repository.submitScrobble(song.id)
                }
            }
        }
    }

    fun play(songs: List<Song>, startIndex: Int = 0) {
        viewModelScope.launch {
            val urls = repository.urls(songs)
            if (urls == null) {
                _message.value = "无法获取播放地址，请重新登录"
                return@launch
            }
            connection.setQueue(songs, startIndex, urls.streams, urls.artwork)
        }
    }

    fun playNext(song: Song) {
        viewModelScope.launch {
            val snapshot = state.value
            val existingIndex = snapshot.queue.indexOfFirst { it.id == song.id }
            if (existingIndex == snapshot.currentIndex && existingIndex >= 0) {
                _message.value = "《${song.title}》正在播放"
                return@launch
            }
            if (existingIndex == snapshot.currentIndex + 1) {
                _message.value = "《${song.title}》已经是下一首"
                return@launch
            }
            if (existingIndex >= 0) {
                val targetIndex = if (existingIndex < snapshot.currentIndex) {
                    snapshot.currentIndex
                } else {
                    (snapshot.currentIndex + 1).coerceAtMost(snapshot.queue.lastIndex)
                }
                connection.move(existingIndex, targetIndex)
                _message.value = "《${song.title}》将在下一首播放"
                return@launch
            }
            val urls = repository.urls(listOf(song))
            val streamUrl = urls?.streams?.get(song.id)
            if (urls == null || streamUrl == null) {
                _message.value = "无法获取播放地址，请重新登录"
                return@launch
            }
            if (state.value.queue.isEmpty()) {
                connection.setQueue(
                    songs = listOf(song),
                    startIndex = 0,
                    streamUrls = urls.streams,
                    artworkUrls = urls.artwork,
                )
                _message.value = "已开始播放《${song.title}》"
            } else {
                connection.playNext(song, streamUrl, urls.artwork[song.id])
                _message.value = "《${song.title}》将在下一首播放"
            }
        }
    }

    fun togglePlayPause() = connection.togglePlayPause()
    fun next() = connection.next()
    fun previous() = connection.previous()
    fun seekTo(positionMs: Long) = connection.seekTo(positionMs)
    fun remove(index: Int) = connection.remove(index)
    fun move(from: Int, to: Int) = connection.move(from, to)
    fun clearQueue() = connection.clear()

    fun persistNow() {
        if (!restored || state.value.queue.isEmpty()) return
        viewModelScope.launch { repository.persistQueue(state.value) }
    }

    fun cycleMode() {
        val mode = connection.cycleMode()
        viewModelScope.launch { preferences.setMode(mode) }
        _message.value = mode.chineseName()
    }

    fun consumeMessage() {
        _message.value = null
    }

    override fun onCleared() {
        connection.release()
    }
}

private data class LocalPlaybackCheckpoint(
    val songId: String?,
    val currentIndex: Int,
    val positionBucket: Long,
)

private const val LOCAL_CHECKPOINT_INTERVAL_MS = 5_000L

private fun PlaybackMode.chineseName(): String = when (this) {
    PlaybackMode.SEQUENTIAL -> "顺序播放"
    PlaybackMode.REPEAT_ALL -> "列表循环"
    PlaybackMode.REPEAT_ONE -> "单曲循环"
    PlaybackMode.SHUFFLE -> "随机播放"
}
