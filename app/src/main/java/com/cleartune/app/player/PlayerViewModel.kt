package com.cleartune.app.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleartune.core.datastore.PlaybackPreferences
import com.cleartune.core.model.PlaybackMode
import com.cleartune.core.model.Song
import com.cleartune.core.model.InternetRadioStation
import com.cleartune.core.network.RemoteResult
import com.cleartune.app.R
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelChildren

internal enum class SelectedSongAction { START, KEEP, RESUME }

internal fun selectedSongAction(currentId: String?, status: PlaybackStatus, selectedId: String): SelectedSongAction {
    if (currentId != selectedId) return SelectedSongAction.START
    return when (status) {
        PlaybackStatus.PLAYING, PlaybackStatus.BUFFERING, PlaybackStatus.READY -> SelectedSongAction.KEEP
        PlaybackStatus.PAUSED -> SelectedSongAction.RESUME
        else -> SelectedSongAction.START
    }
}

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
    private val _radioState = MutableStateFlow(RadioUiState())
    val radioState = _radioState.asStateFlow()
    private val radioArtworkUri = "android.resource://${context.packageName}/${R.drawable.radio_cover_portrait}"
    private var playRequest = 0L

    fun refreshRadioStations() {
        if (_radioState.value.loading) return
        _radioState.value = _radioState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val result = repository.radioStations()
            if (!repository.isSessionActive()) return@launch
            _radioState.value = when (result) {
                is RemoteResult.Success -> RadioUiState(
                    stations = mergeRadioStations(_radioState.value.stations, result.value),
                )
                else -> _radioState.value.copy(loading = false, error = "电台加载失败，请检查网络后重试")
            }
        }
    }

    fun playRadio(station: InternetRadioStation) {
        if (!repository.isSessionActive()) return
        val uri = runCatching { java.net.URI(station.streamUrl) }.getOrNull()
        if (uri?.scheme?.lowercase() !in setOf("http", "https") || uri?.host.isNullOrBlank()) {
            _message.value = "电台播放地址无效，请在服务器中检查配置"
            return
        }
        ++playRequest
        if (state.value.currentSong?.id == station.playbackId) {
            connection.togglePlayPause()
        } else {
            val stations = _radioState.value.stations.let { current ->
                if (current.any { it.id == station.id }) current.map { if (it.id == station.id) station else it }
                else current + station
            }
            val songs = stations.map { it.asSong() }
            connection.setQueue(
                songs, stations.indexOfFirst { it.id == station.id },
                stations.associate { it.playbackId to it.streamUrl },
                songs.associate { it.id to radioArtworkUri },
            )
        }
    }

    init {
        viewModelScope.launch { repository.syncPendingScrobbles() }
        viewModelScope.launch {
            state.map { it.currentSong?.id to (it.status == PlaybackStatus.ERROR) }
                .distinctUntilChanged()
                .collectLatest { (songId, failed) ->
                    if (!failed) return@collectLatest
                    _message.value = state.value.errorMessage ?: "播放失败，请稍后重试"
                    if (songId != null && state.value.currentSong?.isRadio != true && repository.removeMissingFailedSong(songId)) {
                        _message.value = "歌曲已从服务器移除，曲库已更新"
                        connection.removeSong(songId)
                    }
                }
        }
        viewModelScope.launch {
            val mode = preferences.mode.first()
            connection.setMode(mode)
            state.filter { it.connected }.first()
            if (!connection.hasActiveQueue()) {
                repository.restoreQueue()?.let { restoredQueue ->
                    repository.urls(restoredQueue.songs)?.let { urls ->
                        if (playRequest != 0L) return@let
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
                        if (playRequest != 0L) return@let
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
            var observedSongId: String? = null
            var started = false
            state.map { it.currentSong?.id to it.status }.distinctUntilChanged().collect { (songId, status) ->
                if (songId != observedSongId) {
                    observedSongId = songId
                    submittedSongId = null
                    started = false
                }
                if (songId != null && status == PlaybackStatus.PLAYING && !started && state.value.currentSong?.isRadio != true) {
                    started = true
                    viewModelScope.launch { repository.recordStarted(songId) }
                    viewModelScope.launch {
                        val updated = repository.refreshPlayedSong(songId) ?: return@launch
                        val artwork = repository.urls(listOf(updated))?.artwork?.get(songId)
                        connection.updateSongMetadata(updated, artwork)
                    }
                }
            }
        }
        viewModelScope.launch {
            state.collect { snapshot ->
                val song = snapshot.currentSong ?: return@collect
                if (song.isRadio) return@collect
                val threshold = minOf(240_000L, (snapshot.durationMs / 2).coerceAtLeast(30_000L))
                if (snapshot.positionMs >= threshold && submittedSongId != song.id) {
                    submittedSongId = song.id
                    // A slow server or pending backlog must not block observing the next song.
                    viewModelScope.launch { repository.submitScrobble(song.id) }
                }
            }
        }
    }

    fun play(songs: List<Song>, startIndex: Int = 0) {
        val selected = songs.getOrNull(startIndex) ?: return
        val request = ++playRequest
        if (continueCurrentSongIfSelected(selected.id)) return
        viewModelScope.launch {
            val urls = repository.urls(songs)
            if (request != playRequest) return@launch
            if (urls == null) {
                _message.value = "无法获取播放地址，请重新登录"
                return@launch
            }
            // URL resolution is asynchronous: another tap may have started this song meanwhile.
            if (continueCurrentSongIfSelected(selected.id)) return@launch
            connection.setQueue(songs, startIndex, urls.streams, urls.artwork)
        }
    }

    private fun continueCurrentSongIfSelected(songId: String): Boolean {
        val snapshot = state.value
        return when (selectedSongAction(snapshot.currentSong?.id, snapshot.status, songId)) {
            SelectedSongAction.KEEP -> true
            SelectedSongAction.RESUME -> {
                connection.togglePlayPause()
                true
            }
            SelectedSongAction.START -> false
        }
    }

    fun playNext(song: Song) {
        if (state.value.currentSong?.isRadio == true) {
            play(listOf(song))
            return
        }
        viewModelScope.launch {
            val snapshot = state.value
            val existingIndex = snapshot.queue.indexOfFirst { it.id == song.id }
            if (existingIndex == snapshot.currentIndex && existingIndex >= 0) {
                _message.value = "《${song.title}》正在播放"
                return@launch
            }
            if (existingIndex >= 0) {
                connection.playNext(song)
                _message.value = if (existingIndex == snapshot.currentIndex + 1) {
                    "《${song.title}》已经是下一首"
                } else {
                    "《${song.title}》将在下一首播放"
                }
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
    fun playAt(index: Int) = connection.playAt(index)
    fun remove(index: Int) = connection.remove(index)
    fun removeUndoable(index: Int): Long? = connection.removeUndoable(index)
    fun move(from: Int, to: Int) = connection.move(from, to)
    fun clearQueue() = connection.clear()

    fun metadataChanged(song: Song) = viewModelScope.launch {
        val urls = repository.urls(listOf(song)) ?: return@launch
        connection.updateSongMetadata(song, urls.artwork[song.id])
    }
    fun clearQueueUndoable(): Long? = connection.clearUndoable()
    fun undoQueueMutation(token: Long) = connection.undoQueueMutation(token)
    fun discardQueueUndo(token: Long) = connection.discardQueueUndo(token)

    fun persistNow() {
        if (!restored || state.value.queue.isEmpty()) return
        viewModelScope.launch { repository.persistQueue(state.value) }
    }

    suspend fun endSession() {
        restored = false
        viewModelScope.coroutineContext.cancelChildren()
        connection.endSession()
    }

    fun cycleMode() {
        val mode = connection.cycleMode()
        viewModelScope.launch { preferences.setMode(mode) }
        _message.value = mode.chineseName()
    }

    fun setMode(mode: PlaybackMode) {
        if (state.value.mode == mode) return
        connection.setMode(mode)
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
