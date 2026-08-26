package com.cleartune.app.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleartune.core.model.DownloadItem
import com.cleartune.core.model.Song
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class DownloadViewModel @Inject constructor(
    private val repository: DownloadRepository,
) : ViewModel() {
    val downloads: StateFlow<List<DownloadItem>> = repository.downloads.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    fun download(songs: List<Song>) = viewModelScope.launch { repository.enqueue(songs) }
    fun pause(item: DownloadItem) = viewModelScope.launch { repository.pause(item) }
    fun retry(item: DownloadItem, song: Song) = viewModelScope.launch { repository.retry(item, song) }
    fun delete(item: DownloadItem) = viewModelScope.launch { repository.delete(item) }
}
