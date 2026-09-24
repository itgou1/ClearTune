package com.cleartune.app.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleartune.core.network.RemoteResult
import com.cleartune.core.model.ClearTuneError
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ShareUiState(
    val target: ShareTarget? = null,
    val created: MusicShare? = null,
    val shares: List<MusicShare> = emptyList(),
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val detailId: String? = null,
)

@HiltViewModel
class ShareViewModel @Inject constructor(private val repository: ShareRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(ShareUiState())
    val state = mutableState.asStateFlow()

    fun open(target: ShareTarget) {
        mutableState.update { it.copy(target = target, created = null, error = null) }
    }

    fun close() {
        mutableState.update { it.copy(target = null, created = null, error = null) }
    }

    fun create(description: String, days: Int) {
        val target = mutableState.value.target ?: return
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val result = repository.create(target, description, days)
            mutableState.update { current ->
                if (current.target != target) current.copy(busy = false)
                else when (result) {
                    is RemoteResult.Success -> current.copy(created = result.value, busy = false)
                    is RemoteResult.Failure -> current.copy(busy = false, error = result.error.shareMessage())
                }
            }
        }
    }

    fun refresh() {
        if (mutableState.value.loading) return
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.list()) {
                is RemoteResult.Success -> mutableState.update { it.copy(shares = result.value, loading = false) }
                is RemoteResult.Failure -> mutableState.update { it.copy(loading = false, error = result.error.shareMessage()) }
            }
        }
    }

    fun select(id: String?) {
        mutableState.update { it.copy(detailId = id, error = null) }
    }

    fun update(id: String, description: String, days: Int) {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.update(id, description, days)) {
                is RemoteResult.Success -> {
                    mutableState.update { it.copy(busy = false) }
                    refresh()
                }
                is RemoteResult.Failure -> mutableState.update { it.copy(busy = false, error = result.error.shareMessage()) }
            }
        }
    }

    fun delete(id: String) {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.delete(id)) {
                is RemoteResult.Success -> {
                    mutableState.update { it.copy(detailId = null, busy = false, shares = it.shares.filterNot { item -> item.id == id }) }
                    refresh()
                }
                is RemoteResult.Failure -> mutableState.update { it.copy(busy = false, error = result.error.shareMessage()) }
            }
        }
    }
}

private fun ClearTuneError.shareMessage(): String = when {
    this is ClearTuneError.Server && code == 404 -> "当前服务器未开启或不支持分享"
    this is ClearTuneError.Timeout -> "请求超时，结果可能已生效。请到「我的分享」核对后再重试"
    else -> userMessage
}
