package com.cleartune.core.datastore

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EqualizerBandsMigrationTest {
    @Test fun oldCurveIsInterpolatedAndClampedWithoutResettingUserChoices() = runBlocking {
        val curve = stringPreferencesKey("equalizer_custom_levels")
        val preset = stringPreferencesKey("equalizer_preset")
        val enabled = booleanPreferencesKey("equalizer_enabled")
        val old = preferencesOf(curve to "6,6,0,-3,-6", preset to "CUSTOM", enabled to true)
        assertTrue(EqualizerBandsMigration.shouldMigrate(old))
        val migrated = EqualizerBandsMigration.migrate(old)
        assertEquals(listOf(6, 6, 6, 6, 3, 0, -2, -3, -5, -6), decodeEqualizerLevels(migrated[curve]))
        assertEquals("CUSTOM", migrated[preset])
        assertEquals(true, migrated[enabled])
        assertFalse(EqualizerBandsMigration.shouldMigrate(migrated))
        assertEquals(migrated, EqualizerBandsMigration.migrate(migrated))
    }

    @Test fun flatAndExtremeLegacyCurvesStayWithinTheirOriginalGainEnvelope() {
        assertEquals(List(10) { 0 }, decodeEqualizerLevels("0,0,0,0,0"))
        assertEquals(List(10) { 6 }, decodeEqualizerLevels("99,99,99,99,99"))
        assertEquals(List(10) { -6 }, decodeEqualizerLevels("-99,-99,-99,-99,-99"))
        assertEquals(List(10) { -2 }, decodeEqualizerLevels("-2,-2,-2,-2,-2"))
    }

    @Test fun tenBandValuesSurviveRoundTripAndCorruptValuesDoNotShiftBands() {
        val levels = listOf(-6, -5, -4, -3, -2, 0, 1, 2, 4, 6)
        assertEquals(levels, decodeEqualizerLevels(levels.joinToString(",")))
        listOf(null, "", "1,2", "1,2,bad,3,4,5", "1,2,3,4,5,").forEach {
            assertEquals(DEFAULT_EQUALIZER_LEVELS_DB, decodeEqualizerLevels(it))
        }
        EqualizerPreset.entries.forEach {
            val active = EqualizerSettings(preset = it).activeLevelsDb
            assertEquals(10, active.size)
            assertTrue(active.all { gain -> gain in -6..6 })
        }
    }
}
