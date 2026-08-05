package com.cleartune.feature.settings

import com.cleartune.core.model.ReducedMotionMode
import com.cleartune.core.model.SettingsCommand
import com.cleartune.core.model.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
        repository.dispatch(SettingsProductCommand.SetWifiOnlyDownloads(false))

        val restored = PersistentSettingsRepository(storage).productSettings.first()

        assertFalse(restored.restoreQueue)
        assertFalse(restored.pauseOnHeadphoneDisconnect)
        assertTrue(restored.offlineCacheEnabled)
        assertTrue(restored.backgroundPlayback)
        assertFalse(restored.dynamicBackground)
        assertFalse(restored.wifiOnlyDownloads)
    }

    @Test
    fun `reduced motion resolves explicit and system modes`() {
        assertTrue(isReducedMotionEnabled(ReducedMotionMode.ON, systemAnimationsEnabled = true))
        assertFalse(isReducedMotionEnabled(ReducedMotionMode.OFF, systemAnimationsEnabled = false))
        assertTrue(isReducedMotionEnabled(ReducedMotionMode.SYSTEM, systemAnimationsEnabled = false))
        assertFalse(isReducedMotionEnabled(ReducedMotionMode.SYSTEM, systemAnimationsEnabled = true))
    }

    @Test
    fun `unbound product operations are unavailable and cannot silently dispatch`() = runTest {
        var actions = 0
        val repository = PersistentSettingsRepository(MapSettingsStorage()) { actions++ }

        val state = repository.productSettings.first()
        assertTrue(state.scanLibrary is SettingsOperationState.Unavailable)
        assertTrue(state.cleanUpCache is SettingsOperationState.Unavailable)
        assertTrue(state.openLicenses is SettingsOperationState.Unavailable)
        try {
            repository.dispatch(SettingsProductCommand.ScanLibrary)
            fail("Unavailable operation should reject dispatch")
        } catch (_: IllegalStateException) {
        }
        assertEquals(0, actions)
    }

    @Test
    fun `bound product operation reports success after action completes`() = runTest {
        var actions = 0
        val repository = PersistentSettingsRepository(
            storage = MapSettingsStorage(),
            capabilities = setOf(SettingsOperation.SCAN_LIBRARY),
            onAction = { actions++ },
        )

        repository.dispatch(SettingsProductCommand.ScanLibrary)

        assertEquals(1, actions)
        assertTrue(repository.productSettings.first().scanLibrary is SettingsOperationState.Success)
    }

    @Test
    fun `bound product operation exposes running then error state`() = runTest {
        lateinit var repository: PersistentSettingsRepository
        repository = PersistentSettingsRepository(
            storage = MapSettingsStorage(),
            capabilities = setOf(SettingsOperation.CLEAN_UP_CACHE),
            onAction = {
                assertTrue(repository.productSettings.first().cleanUpCache is SettingsOperationState.Running)
                error("cleanup failed")
            },
        )

        repository.dispatch(SettingsProductCommand.CleanUpCache)

        val error = repository.productSettings.first().cleanUpCache as SettingsOperationState.Error
        assertEquals("cleanup failed", error.message)
    }

    @Test
    fun `licenses row is absent while the action is unavailable`() {
        assertFalse(licensesAreVisible(SettingsOperationState.Unavailable("not bundled")))
        assertTrue(licensesAreVisible(SettingsOperationState.Ready))
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
