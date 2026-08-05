package com.cleartune.app

import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppProductSettingsControllerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun refresh_publishes_growth_and_cleanup_to_the_live_flow() = runBlocking {
        val root = temporaryFolder.newFolder("cache")
        val controller = AppProductSettingsController(
            cacheRoot = root,
            scanLibrary = {},
            cleanUpCache = { clearContainedCache(root) },
        )
        assertEquals(0, controller.productSettings.first().cachedBytes)

        File(root, "nested").apply(File::mkdirs).resolve("payload.bin").writeBytes(ByteArray(37))
        controller.dispatch(com.cleartune.feature.settings.SettingsProductCommand.RefreshCacheUsage)
        assertEquals(37, controller.productSettings.first().cachedBytes)

        controller.dispatch(com.cleartune.feature.settings.SettingsProductCommand.CleanUpCache)
        assertEquals(0, controller.productSettings.first().cachedBytes)
    }

    @Test
    fun wifi_only_download_change_is_persisted_and_rebuilds_active_work() = runBlocking {
        val root = temporaryFolder.newFolder("cache-policy")
        val rebuilt = mutableListOf<Boolean>()
        val controller = AppProductSettingsController(
            cacheRoot = root,
            scanLibrary = {},
            cleanUpCache = {},
            rebuildDownloadConstraints = { rebuilt += it },
        )

        controller.dispatch(com.cleartune.feature.settings.SettingsProductCommand.SetWifiOnlyDownloads(false))

        assertFalse(controller.productSettings.first().wifiOnlyDownloads)
        assertEquals(listOf(false), rebuilt)
    }
}
