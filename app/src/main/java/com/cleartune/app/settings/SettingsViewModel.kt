package com.cleartune.app.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleartune.core.datastore.AppPreferences
import com.cleartune.core.datastore.AppSettings
import com.cleartune.core.datastore.EqualizerPreset
import com.cleartune.core.datastore.MobileAudioQuality
import com.cleartune.core.datastore.ThemeMode
import com.cleartune.core.player.PLAYBACK_CACHE_DIRECTORY_NAME
import java.io.File
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
    val ignored: Boolean = false,
    val message: String? = null,
)

data class CacheUiState(
    val sizeBytes: Long = 0,
    val playbackSizeBytes: Long = 0,
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
            val now = System.currentTimeMillis()
            val automaticCheckEnabled = preferences.settings.first().checkUpdates
            val lastCheck = preferences.lastUpdateCheckEpochMs()
            if (automaticCheckEnabled && isAutomaticUpdateCheckDue(lastCheck, now)) {
                performUpdateCheck(now)
            }
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
    fun selectEqualizerPreset(value: EqualizerPreset) = viewModelScope.launch {
        preferences.setEqualizerPreset(value)
        preferences.setEqualizerEnabled(true)
    }
    fun setEqualizerCustomLevels(value: List<Int>) = viewModelScope.launch {
        preferences.setEqualizerCustomLevels(value)
    }
    fun setWifiOnly(value: Boolean) = viewModelScope.launch { preferences.setWifiOnlyDownloads(value) }
    fun setPlaybackCacheSizeMb(value: Int) = viewModelScope.launch {
        preferences.setPlaybackCacheSizeMb(value)
    }
    fun setMobileAudioQuality(value: MobileAudioQuality) = viewModelScope.launch {
        preferences.setMobileAudioQuality(value)
    }
    fun setCheckUpdates(value: Boolean) = viewModelScope.launch {
        preferences.setCheckUpdates(value)
        if (value) {
            val now = System.currentTimeMillis()
            if (isAutomaticUpdateCheckDue(preferences.lastUpdateCheckEpochMs(), now)) {
                performUpdateCheck(now)
            }
        }
    }

    fun checkUpdate() {
        viewModelScope.launch { performUpdateCheck(System.currentTimeMillis()) }
    }

    private suspend fun performUpdateCheck(now: Long) {
        if (_updateState.value.checking) return
        _updateState.value = UpdateUiState(checking = true)
        preferences.setLastUpdateCheckEpochMs(now)
        val ignoredVersion = preferences.ignoredUpdateVersion()
        _updateState.value = updateChecker.check().fold(
            onSuccess = { release ->
                val ignored = release.newer && release.identity == ignoredVersion
                UpdateUiState(
                    release = release,
                    ignored = ignored,
                    message = when {
                        ignored -> "版本 ${release.version} 已忽略"
                        release.newer -> "发现新版本 ${release.version}"
                        else -> "当前已是最新版本"
                    },
                )
            },
            onFailure = { UpdateUiState(message = "暂时无法检查更新，请稍后重试") },
        )
    }

    fun ignoreUpdate(release: UpdateRelease) {
        viewModelScope.launch {
            preferences.setIgnoredUpdateVersion(release.identity)
            _updateState.value = _updateState.value.copy(
                ignored = true,
                message = "版本 ${release.version} 已忽略",
            )
        }
    }

    fun refreshCacheSize() {
        viewModelScope.launch {
            val (size, playbackSize) = withContext(Dispatchers.IO) {
                cacheSize() to directorySize(File(context.filesDir, PLAYBACK_CACHE_DIRECTORY_NAME))
            }
            _cacheState.value = _cacheState.value.copy(
                sizeBytes = size,
                playbackSizeBytes = playbackSize,
            )
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
                playbackSizeBytes = _cacheState.value.playbackSizeBytes,
                message = if (cleared) "缓存已清理" else "部分缓存暂时无法清理",
            )
        }
    }

    private fun cacheSize(): Long = context.cacheDir.walkTopDown()
        .filter { it.isFile }
        .sumOf { it.length() }

    private fun directorySize(directory: File): Long = directory
        .takeIf(File::exists)
        ?.walkTopDown()
        ?.filter { it.isFile }
        ?.sumOf { it.length() }
        ?: 0
}

internal fun isAutomaticUpdateCheckDue(lastCheckEpochMs: Long, nowEpochMs: Long): Boolean =
    lastCheckEpochMs <= 0L ||
        nowEpochMs < lastCheckEpochMs ||
        nowEpochMs - lastCheckEpochMs >= AUTOMATIC_UPDATE_CHECK_INTERVAL_MS

private const val AUTOMATIC_UPDATE_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1_000L
