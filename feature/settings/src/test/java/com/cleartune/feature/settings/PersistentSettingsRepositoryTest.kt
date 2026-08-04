package com.cleartune.feature.settings

import com.cleartune.core.model.ReducedMotionMode
import com.cleartune.core.model.SettingsCommand
import com.cleartune.core.model.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `product playback and storage preferences persist across recreation`() = runTest {
        val storage = MapSettingsStorage()
        val repository = PersistentSettingsRepository(storage)
        repository.dispatch(SettingsProductCommand.SetRestoreQueue(false))
        repository.dispatch(SettingsProductCommand.SetPauseOnHeadphoneDisconnect(false))
        repository.dispatch(SettingsProductCommand.SetOfflineCacheEnabled(true))
        repository.dispatch(SettingsProductCommand.SetBackgroundPlayback(true))
        repository.dispatch(SettingsProductCommand.SetDynamicBackground(false))

        val restored = PersistentSettingsRepository(storage).productSettings.first()

        assertFalse(restored.restoreQueue)
        assertFalse(restored.pauseOnHeadphoneDisconnect)
        assertTrue(restored.offlineCacheEnabled)
        assertTrue(restored.backgroundPlayback)
        assertFalse(restored.dynamicBackground)
    }

    @Test
    fun `reduced motion resolves explicit and system modes`() {
        assertTrue(isReducedMotionEnabled(ReducedMotionMode.ON, systemAnimationsEnabled = true))
        assertFalse(isReducedMotionEnabled(ReducedMotionMode.OFF, systemAnimationsEnabled = false))
        assertTrue(isReducedMotionEnabled(ReducedMotionMode.SYSTEM, systemAnimationsEnabled = false))
        assertFalse(isReducedMotionEnabled(ReducedMotionMode.SYSTEM, systemAnimationsEnabled = true))
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
