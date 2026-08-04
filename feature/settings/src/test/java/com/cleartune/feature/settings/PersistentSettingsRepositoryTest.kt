package com.cleartune.feature.settings

import com.cleartune.core.model.ReducedMotionMode
import com.cleartune.core.model.SettingsCommand
import com.cleartune.core.model.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PersistentSettingsRepositoryTest {
    @Test
    fun `updates are persisted and restored`() = runTest {
        val storage = MapSettingsStorage()
        val repository = PersistentSettingsRepository(storage)
        repository.update(SettingsCommand.SetTheme(ThemeMode.DARK))
        repository.update(SettingsCommand.SetReducedMotion(ReducedMotionMode.ON))

        val restored = PersistentSettingsRepository(storage).settings.first()

        assertEquals(ThemeMode.DARK, restored.themeMode)
        assertEquals(ReducedMotionMode.ON, restored.reducedMotionMode)
    }

    @Test
    fun `invalid stored values fall back to system defaults`() = runTest {
        val repository = PersistentSettingsRepository(
            MapSettingsStorage(mutableMapOf("theme" to "invalid", "motion" to "invalid")),
        )

        val settings = repository.settings.first()

        assertEquals(ThemeMode.SYSTEM, settings.themeMode)
        assertEquals(ReducedMotionMode.SYSTEM, settings.reducedMotionMode)
    }
}

private class MapSettingsStorage(
    private val values: MutableMap<String, String> = mutableMapOf(),
) : SettingsStorage {
    override fun getString(key: String): String? = values[key]
    override fun putString(key: String, value: String) {
        values[key] = value
    }
}
