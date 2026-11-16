package com.cleartune.app

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.cleartune.core.datastore.AppPreferences
import com.cleartune.core.designsystem.ClearTuneTheme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CacheManagementUiTest {
    @get:Rule val ui = createComposeRule()
    @Test fun sliderAndCustomCapacityOnlyPersistOnSave() {
        val preferences = AppPreferences(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = runBlocking { preferences.settings.first().playbackCacheSizeMb }
        runBlocking { preferences.setPlaybackCacheSizeMb(512) }
        try {
            ui.setContent { ClearTuneTheme { CacheManagementScreen {} } }
            openCapacity()
            ui.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
                .performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(4f) }
            assertEquals(512, runBlocking { preferences.settings.first().playbackCacheSizeMb })
            val image = ui.onNode(isDialog()).captureToImage().asAndroidBitmap()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val screenshot = context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "cache-management.png")
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ClearTuneTest")
                })!!
            context.contentResolver.openOutputStream(screenshot)!!.use {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            ui.onNodeWithText("自定义容量").performScrollTo().performClick()
            ui.onNode(hasSetTextAction()).performTextReplacement("1536")
            ui.onNodeWithText("选择").performClick()
            assertEquals(512, runBlocking { preferences.settings.first().playbackCacheSizeMb })
            ui.onNodeWithText("有未保存的修改").assertExists()
            ui.onNodeWithText("保存设置").performClick()
            ui.waitUntil(10000) { runBlocking { preferences.settings.first().playbackCacheSizeMb == 1536 } }
            ui.waitUntil(10000) { ui.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
            openCapacity()
            ui.onNodeWithText("保存设置").assertIsNotEnabled()
            ui.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
                .performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(4f) }
            ui.onNodeWithText("取消").performClick()
            ui.onNodeWithText("放弃修改").performClick()
            assertEquals(1536, runBlocking { preferences.settings.first().playbackCacheSizeMb })
            openCapacity()
            ui.onNodeWithText("保存设置").assertIsNotEnabled()
        } finally { runBlocking { preferences.setPlaybackCacheSizeMb(original) } }
    }
    @Test fun captureRedesignedPage() {
        ui.setContent { ClearTuneTheme(darkTheme = false) { CacheManagementScreen {} } }
        ui.onNodeWithText("自定义容量").assertDoesNotExist()
        ui.waitForIdle()
        saveScreenshot("cache-redesign-top.png")
        ui.onNodeWithText("本机下载").performScrollTo().assertIsDisplayed()
        ui.waitForIdle()
        saveScreenshot("cache-redesign-bottom.png")
    }

    private fun openCapacity() {
        ui.waitUntil(10000) {
            ui.onAllNodes(hasText("调整上限") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithText("调整上限").performScrollTo().performClick()
    }

    private fun saveScreenshot(name: String) {
        val image = ui.onRoot().captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ClearTuneTest")
            })!!
        context.contentResolver.openOutputStream(uri)!!.use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun largeTextDarkThemeKeepsControlsScrollableAndReadable() {
        ui.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(2.75f, 1.6f)
            ) { ClearTuneTheme(darkTheme = true) { CacheManagementScreen {} } }
        }
        openCapacity()
        ui.onNodeWithText("自定义容量").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("保存设置").assertIsDisplayed()
        ui.onNodeWithText("取消").performClick()
        ui.onNodeWithText("图片与临时缓存").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("本机下载").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("本机下载").assertHasClickAction()
        val image = ui.onRoot().captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "cache-management-large-text.png")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ClearTuneTest")
            })!!
        context.contentResolver.openOutputStream(uri)!!.use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

}
