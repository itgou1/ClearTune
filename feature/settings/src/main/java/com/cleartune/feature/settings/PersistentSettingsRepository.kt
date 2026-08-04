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

interface SettingsStorage {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}

class PersistentSettingsRepository(
    private val storage: SettingsStorage,
) : SettingsRepository {
    private val mutex = Mutex()
    private val state = MutableStateFlow(
        AppSettings(
            themeMode = enumValueOrDefault(storage.getString(THEME_KEY), ThemeMode.SYSTEM),
            reducedMotionMode = enumValueOrDefault(storage.getString(MOTION_KEY), ReducedMotionMode.SYSTEM),
        ),
    )

    override val settings: Flow<AppSettings> = state

    override suspend fun update(command: SettingsCommand) = mutex.withLock {
        state.value = when (command) {
            is SettingsCommand.SetTheme -> state.value.copy(themeMode = command.mode)
                .also { storage.putString(THEME_KEY, command.mode.name) }
            is SettingsCommand.SetReducedMotion -> state.value.copy(reducedMotionMode = command.mode)
                .also { storage.putString(MOTION_KEY, command.mode.name) }
        }
    }

    private companion object {
        const val THEME_KEY = "theme"
        const val MOTION_KEY = "motion"
    }
}

private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, default: T): T =
    enumValues<T>().firstOrNull { it.name == value } ?: default
