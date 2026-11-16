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

    init { viewModelScope.launch { repository.monitor() } }

    val downloads: StateFlow<List<DownloadItem>> = repository.downloads.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    fun download(songs: List<Song>) = action {
        val result = repository.enqueue(songs)
        downloadEnqueueMessage(result)?.let { _messages.emit(it) }
    }
    fun pause(item: DownloadItem) = action { repository.pause(item) }
    fun retry(item: DownloadItem, song: Song) = action {
        downloadEnqueueMessage(repository.retry(item, song))?.let { _messages.emit(it) }
    }
    fun delete(item: DownloadItem) = action { repository.delete(item) }

    private fun action(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
        } catch (_: Exception) { _messages.emit("后台任务操作失败，请稍后重试") }
    }
}

internal fun downloadEnqueueMessage(result: DownloadEnqueueResult): String? = buildList {
    if (result.queuedCount > 0) add("已加入 ${result.queuedCount} 首歌曲到下载队列" +
        if (result.waitingForWifi) "，等待非计费 Wi-Fi 网络" else "")
    if (result.alreadyDownloadedCount > 0) add("已跳过 ${result.alreadyDownloadedCount} 首已下载歌曲")
    if (result.failedCount > 0) add("${result.failedCount} 个任务提交失败，请在下载列表重试")
}.takeIf { it.isNotEmpty() }?.joinToString("；")
