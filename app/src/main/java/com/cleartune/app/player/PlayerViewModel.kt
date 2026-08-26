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

    fun togglePlayPause() = connection.togglePlayPause()
    fun next() = connection.next()
    fun previous() = connection.previous()
    fun seekTo(positionMs: Long) = connection.seekTo(positionMs)
    fun remove(index: Int) = connection.remove(index)
    fun move(from: Int, to: Int) = connection.move(from, to)
    fun clearQueue() = connection.clear()

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

private fun PlaybackMode.chineseName(): String = when (this) {
    PlaybackMode.SEQUENTIAL -> "顺序播放"
    PlaybackMode.REPEAT_ALL -> "列表循环"
    PlaybackMode.REPEAT_ONE -> "单曲循环"
    PlaybackMode.SHUFFLE -> "随机播放"
}
