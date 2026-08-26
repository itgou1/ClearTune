package com.cleartune.app.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleartune.core.model.DownloadItem
import com.cleartune.core.model.Song
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class DownloadViewModel @Inject constructor(
    private val repository: DownloadRepository,
) : ViewModel() {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages = _messages.asSharedFlow()

    val downloads: StateFlow<List<DownloadItem>> = repository.downloads.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    fun download(songs: List<Song>) = viewModelScope.launch {
        val result = repository.enqueue(songs)
        val message = when {
            result.waitingForWifi -> "已加入下载队列，将在连接 Wi-Fi 后开始"
            result.queuedCount > 0 -> "已加入下载队列"
            result.alreadyDownloadedCount > 0 -> "歌曲已下载"
            else -> null
        }
        message?.let { _messages.emit(it) }
    }
    fun pause(item: DownloadItem) = viewModelScope.launch { repository.pause(item) }
    fun retry(item: DownloadItem, song: Song) = viewModelScope.launch { repository.retry(item, song) }
    fun delete(item: DownloadItem) = viewModelScope.launch { repository.delete(item) }
}
