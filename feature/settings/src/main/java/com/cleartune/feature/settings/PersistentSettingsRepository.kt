package com.cleartune.feature.settings

import com.cleartune.core.contracts.SettingsRepository
import com.cleartune.core.model.AppSettings
import com.cleartune.core.model.ReducedMotionMode
import com.cleartune.core.model.SettingsCommand
import com.cleartune.core.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SettingsOperation { SCAN_LIBRARY, CLEAN_UP_CACHE, OPEN_LICENSES }

sealed interface SettingsOperationState {
    data class Unavailable(val reason: String) : SettingsOperationState
    data object Ready : SettingsOperationState
    data object Running : SettingsOperationState
    data class Success(val message: String) : SettingsOperationState
    data class Error(val message: String) : SettingsOperationState
}

data class SettingsProductState(
    val restoreQueue: Boolean = true,
    val pauseOnHeadphoneDisconnect: Boolean = true,
    val offlineCacheEnabled: Boolean = false,
    val backgroundPlayback: Boolean = false,
    val dynamicBackground: Boolean = true,
    val cacheLimitMb: Int = 512,
    val cachedBytes: Long = 0,
    val offlineTrackCount: Int = 0,
    val scanLibrary: SettingsOperationState = SettingsOperationState.Unavailable("Library scanning is not configured yet"),
    val cleanUpCache: SettingsOperationState = SettingsOperationState.Unavailable("Cache cleanup is not configured yet"),
    val openLicenses: SettingsOperationState = SettingsOperationState.Unavailable("License information is not configured yet"),
)

sealed interface SettingsProductCommand {
    data class SetRestoreQueue(val enabled: Boolean) : SettingsProductCommand
    data class SetPauseOnHeadphoneDisconnect(val enabled: Boolean) : SettingsProductCommand
    data class SetOfflineCacheEnabled(val enabled: Boolean) : SettingsProductCommand
    data class SetBackgroundPlayback(val enabled: Boolean) : SettingsProductCommand
    data class SetDynamicBackground(val enabled: Boolean) : SettingsProductCommand
    data class SetCacheLimitMb(val megabytes: Int) : SettingsProductCommand
    data object RefreshCacheUsage : SettingsProductCommand
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
    capabilities: Set<SettingsOperation> = emptySet(),
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
            scanLibrary = operationState(SettingsOperation.SCAN_LIBRARY, capabilities),
            cleanUpCache = operationState(SettingsOperation.CLEAN_UP_CACHE, capabilities),
            openLicenses = operationState(SettingsOperation.OPEN_LICENSES, capabilities),
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
            SettingsProductCommand.RefreshCacheUsage -> {
                onAction(command)
                productState.value
            }
            SettingsProductCommand.ScanLibrary,
            SettingsProductCommand.CleanUpCache,
            SettingsProductCommand.OpenLicenses,
            -> runOperation(command)
        }
    }

    private suspend fun runOperation(command: SettingsProductCommand): SettingsProductState {
        val operation = command.operation()
        val current = productState.value.operationState(operation)
        if (current is SettingsOperationState.Unavailable) error(current.reason)
        check(current !is SettingsOperationState.Running) { "Operation is already running" }
        productState.value = productState.value.withOperation(operation, SettingsOperationState.Running)
        return try {
            onAction(command)
            productState.value.withOperation(operation, SettingsOperationState.Success("Completed"))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            productState.value.withOperation(
                operation,
                SettingsOperationState.Error(error.message?.takeIf(String::isNotBlank) ?: "Operation failed"),
            )
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

fun SettingsProductState.operationState(operation: SettingsOperation): SettingsOperationState = when (operation) {
    SettingsOperation.SCAN_LIBRARY -> scanLibrary
    SettingsOperation.CLEAN_UP_CACHE -> cleanUpCache
    SettingsOperation.OPEN_LICENSES -> openLicenses
}

private fun SettingsProductState.withOperation(
    operation: SettingsOperation,
    state: SettingsOperationState,
): SettingsProductState = when (operation) {
    SettingsOperation.SCAN_LIBRARY -> copy(scanLibrary = state)
    SettingsOperation.CLEAN_UP_CACHE -> copy(cleanUpCache = state)
    SettingsOperation.OPEN_LICENSES -> copy(openLicenses = state)
}

private fun SettingsProductCommand.operation(): SettingsOperation = when (this) {
    SettingsProductCommand.ScanLibrary -> SettingsOperation.SCAN_LIBRARY
    SettingsProductCommand.CleanUpCache -> SettingsOperation.CLEAN_UP_CACHE
    SettingsProductCommand.OpenLicenses -> SettingsOperation.OPEN_LICENSES
    else -> error("Command is not an operation")
}

private fun operationState(
    operation: SettingsOperation,
    capabilities: Set<SettingsOperation>,
): SettingsOperationState = if (operation in capabilities) {
    SettingsOperationState.Ready
} else {
    SettingsOperationState.Unavailable(
        when (operation) {
            SettingsOperation.SCAN_LIBRARY -> "Library scanning is not configured yet"
            SettingsOperation.CLEAN_UP_CACHE -> "Cache cleanup is not configured yet"
            SettingsOperation.OPEN_LICENSES -> "License information is not configured yet"
        },
    )
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
