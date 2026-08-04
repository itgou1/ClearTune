package com.cleartune.feature.settings

import com.cleartune.core.contracts.SettingsRepository
import com.cleartune.core.model.AppSettings
import com.cleartune.core.model.ReducedMotionMode
import com.cleartune.core.model.SettingsCommand
import com.cleartune.core.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SettingsProductState(
    val restoreQueue: Boolean = true,
    val pauseOnHeadphoneDisconnect: Boolean = true,
    val offlineCacheEnabled: Boolean = false,
    val backgroundPlayback: Boolean = false,
    val dynamicBackground: Boolean = true,
    val cacheLimitMb: Int = 512,
    val cachedBytes: Long = 0,
    val offlineTrackCount: Int = 0,
)

sealed interface SettingsProductCommand {
    data class SetRestoreQueue(val enabled: Boolean) : SettingsProductCommand
    data class SetPauseOnHeadphoneDisconnect(val enabled: Boolean) : SettingsProductCommand
    data class SetOfflineCacheEnabled(val enabled: Boolean) : SettingsProductCommand
    data class SetBackgroundPlayback(val enabled: Boolean) : SettingsProductCommand
    data class SetDynamicBackground(val enabled: Boolean) : SettingsProductCommand
    data class SetCacheLimitMb(val megabytes: Int) : SettingsProductCommand
    data object ScanLibrary : SettingsProductCommand
    data object CleanUpCache : SettingsProductCommand
    data object OpenLicenses : SettingsProductCommand
}

interface SettingsProductController {
    val productSettings: Flow<SettingsProductState>
    suspend fun dispatch(command: SettingsProductCommand)
}

interface SettingsStorage {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}

class PersistentSettingsRepository(
    private val storage: SettingsStorage,
    private val onAction: suspend (SettingsProductCommand) -> Unit = {},
) : SettingsRepository, SettingsProductController {
    private val mutex = Mutex()
    private val state = MutableStateFlow(
        AppSettings(
            themeMode = enumValueOrDefault(storage.getString(THEME_KEY), ThemeMode.SYSTEM),
            reducedMotionMode = enumValueOrDefault(storage.getString(MOTION_KEY), ReducedMotionMode.SYSTEM),
        ),
    )

    override val settings: Flow<AppSettings> = state
    private val productState = MutableStateFlow(
        SettingsProductState(
            restoreQueue = storage.boolean(RESTORE_QUEUE_KEY, true),
            pauseOnHeadphoneDisconnect = storage.boolean(HEADPHONE_PAUSE_KEY, true),
            offlineCacheEnabled = storage.boolean(OFFLINE_CACHE_KEY, false),
            backgroundPlayback = storage.boolean(BACKGROUND_PLAYBACK_KEY, false),
            dynamicBackground = storage.boolean(DYNAMIC_BACKGROUND_KEY, true),
            cacheLimitMb = storage.getString(CACHE_LIMIT_KEY)?.toIntOrNull()?.coerceIn(64, 8_192) ?: 512,
        ),
    )
    override val productSettings: Flow<SettingsProductState> = productState

    override suspend fun update(command: SettingsCommand) = mutex.withLock {
        state.value = when (command) {
            is SettingsCommand.SetTheme -> state.value.copy(themeMode = command.mode)
                .also { storage.putString(THEME_KEY, command.mode.name) }
            is SettingsCommand.SetReducedMotion -> state.value.copy(reducedMotionMode = command.mode)
                .also { storage.putString(MOTION_KEY, command.mode.name) }
        }
    }

    override suspend fun dispatch(command: SettingsProductCommand) = mutex.withLock {
        productState.value = when (command) {
            is SettingsProductCommand.SetRestoreQueue -> productState.value.copy(restoreQueue = command.enabled)
                .persist(RESTORE_QUEUE_KEY, command.enabled)
            is SettingsProductCommand.SetPauseOnHeadphoneDisconnect ->
                productState.value.copy(pauseOnHeadphoneDisconnect = command.enabled)
                    .persist(HEADPHONE_PAUSE_KEY, command.enabled)
            is SettingsProductCommand.SetOfflineCacheEnabled ->
                productState.value.copy(offlineCacheEnabled = command.enabled)
                    .persist(OFFLINE_CACHE_KEY, command.enabled)
            is SettingsProductCommand.SetBackgroundPlayback ->
                productState.value.copy(backgroundPlayback = command.enabled)
                    .persist(BACKGROUND_PLAYBACK_KEY, command.enabled)
            is SettingsProductCommand.SetDynamicBackground ->
                productState.value.copy(dynamicBackground = command.enabled)
                    .persist(DYNAMIC_BACKGROUND_KEY, command.enabled)
            is SettingsProductCommand.SetCacheLimitMb -> {
                val limit = command.megabytes.coerceIn(64, 8_192)
                productState.value.copy(cacheLimitMb = limit).also { storage.putString(CACHE_LIMIT_KEY, "$limit") }
            }
            SettingsProductCommand.ScanLibrary,
            SettingsProductCommand.CleanUpCache,
            SettingsProductCommand.OpenLicenses,
            -> {
                onAction(command)
                productState.value
            }
        }
    }

    private fun SettingsProductState.persist(key: String, value: Boolean): SettingsProductState =
        also { storage.putString(key, value.toString()) }

    private companion object {
        const val THEME_KEY = "theme"
        const val MOTION_KEY = "motion"
        const val RESTORE_QUEUE_KEY = "restore_queue"
        const val HEADPHONE_PAUSE_KEY = "headphone_pause"
        const val OFFLINE_CACHE_KEY = "offline_cache"
        const val BACKGROUND_PLAYBACK_KEY = "background_playback"
        const val DYNAMIC_BACKGROUND_KEY = "dynamic_background"
        const val CACHE_LIMIT_KEY = "cache_limit_mb"
    }
}

fun isReducedMotionEnabled(mode: ReducedMotionMode, systemAnimationsEnabled: Boolean): Boolean = when (mode) {
    ReducedMotionMode.ON -> true
    ReducedMotionMode.OFF -> false
    ReducedMotionMode.SYSTEM -> !systemAnimationsEnabled
}

private fun SettingsStorage.boolean(key: String, default: Boolean): Boolean =
    getString(key)?.toBooleanStrictOrNull() ?: default

private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, default: T): T =
    enumValues<T>().firstOrNull { it.name == value } ?: default
