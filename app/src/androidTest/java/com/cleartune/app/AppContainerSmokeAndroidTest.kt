package com.cleartune.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppContainerSmokeAndroidTest {
    @Test
    fun realApplicationContainerExposesProductionRuntimeBindings() {
        val application = ApplicationProvider.getApplicationContext<Context>().applicationContext as ClearTuneApplication
        val smoke = application.container.smokeSnapshot()

        assertEquals("RoomLibraryRepository", smoke.libraryRepository)
        assertEquals("RoomLibraryRepository", smoke.sourceRepository)
        assertEquals("RoomPlaybackQueueAdapter", smoke.queueRepository)
        assertFalse(smoke.workerFactory.startsWith("Fake"))
        assertTrue(smoke.hasCredentialResolver)
        assertTrue(smoke.hasRuntimeSettings)
    }
}
