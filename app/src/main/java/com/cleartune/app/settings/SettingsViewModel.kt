package com.cleartune.app.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleartune.core.datastore.AppPreferences
import com.cleartune.core.datastore.AppSettings
import com.cleartune.core.datastore.EqualizerPreset
import com.cleartune.core.datastore.MobileAudioQuality
import com.cleartune.core.datastore.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UpdateUiState(
    val checking: Boolean = false,
    val release: UpdateRelease? = null,
    val message: String? = null,
)

data class CacheUiState(
    val sizeBytes: Long = 0,
    val clearing: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: AppPreferences,
    private val updateChecker: UpdateChecker,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = preferences.settings.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AppSettings(),
    )
    private val _updateState = MutableStateFlow(UpdateUiState())
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()
    private val _cacheState = MutableStateFlow(CacheUiState())
    val cacheState: StateFlow<CacheUiState> = _cacheState.asStateFlow()

    init {
        viewModelScope.launch {
            if (preferences.settings.first().checkUpdates) checkUpdate()
        }
        refreshCacheSize()
    }

    fun setTheme(value: ThemeMode) = viewModelScope.launch { preferences.setThemeMode(value) }
    fun setVolumeNormalization(value: Boolean) = viewModelScope.launch {
        preferences.setVolumeNormalizationEnabled(value)
    }
    fun setEqualizerEnabled(value: Boolean) = viewModelScope.launch {
        preferences.setEqualizerEnabled(value)
    }
    fun setEqualizerPreset(value: EqualizerPreset) = viewModelScope.launch {
        preferences.setEqualizerPreset(value)
    }
    fun setEqualizerCustomLevels(value: List<Int>) = viewModelScope.launch {
        preferences.setEqualizerCustomLevels(value)
    }
    fun setWifiOnly(value: Boolean) = viewModelScope.launch { preferences.setWifiOnlyDownloads(value) }
    fun setMobileAudioQuality(value: MobileAudioQuality) = viewModelScope.launch {
        preferences.setMobileAudioQuality(value)
    }
    fun setCheckUpdates(value: Boolean) = viewModelScope.launch { preferences.setCheckUpdates(value) }

    fun checkUpdate() {
        viewModelScope.launch {
            _updateState.value = UpdateUiState(checking = true)
            _updateState.value = updateChecker.check().fold(
                onSuccess = { release ->
                    UpdateUiState(
                        release = release,
                        message = if (release.newer) "发现新版本 ${release.version}" else "当前已是最新版本",
                    )
                },
                onFailure = { UpdateUiState(message = "暂时无法检查更新，请稍后重试") },
            )
        }
    }

    fun refreshCacheSize() {
        viewModelScope.launch {
            val size = withContext(Dispatchers.IO) {
                context.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            }
            _cacheState.value = _cacheState.value.copy(sizeBytes = size)
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            _cacheState.value = _cacheState.value.copy(clearing = true, message = null)
            val cleared = withContext(Dispatchers.IO) {
                context.cacheDir.listFiles()?.all { it.deleteRecursively() } ?: true
            }
            _cacheState.value = CacheUiState(
                sizeBytes = if (cleared) 0 else cacheSize(),
                message = if (cleared) "缓存已清理" else "部分缓存暂时无法清理",
            )
        }
    }

    private fun cacheSize(): Long = context.cacheDir.walkTopDown()
        .filter { it.isFile }
        .sumOf { it.length() }
}
