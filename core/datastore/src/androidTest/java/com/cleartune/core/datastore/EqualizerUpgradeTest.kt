package com.cleartune.core.datastore

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EqualizerUpgradeTest {
    @Test fun persistedFiveBandSettingsAreMigratedBeforeFirstReadAfterUpgrade() = runBlocking {
        val file = File.createTempFile("eq-upgrade-", ".preferences_pb", androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        file.delete()
        val curve = stringPreferencesKey("equalizer_custom_levels")
        val enabled = booleanPreferencesKey("equalizer_enabled")
        val preset = stringPreferencesKey("equalizer_preset")
        val oldJob = Job()
        val newJob = Job()
        try {
            val oldStore = PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + oldJob), produceFile = { file },
            )
            oldStore.edit { it[curve] = "6,6,0,-3,-6"; it[enabled] = true; it[preset] = "CUSTOM" }
            oldJob.cancelAndJoin()
            val newStore = PreferenceDataStoreFactory.create(
                migrations = listOf(EqualizerBandsMigration),
                scope = CoroutineScope(Dispatchers.IO + newJob), produceFile = { file },
            )
            val first = newStore.data.first()
            assertEquals("6,6,6,6,3,0,-2,-3,-5,-6", first[curve])
            assertEquals(true, first[enabled])
            assertEquals("CUSTOM", first[preset])
        } finally {
            oldJob.cancelAndJoin()
            newJob.cancelAndJoin()
            file.delete()
        }
    }

}
