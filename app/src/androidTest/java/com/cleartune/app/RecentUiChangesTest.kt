package com.cleartune.app

import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cleartune.app.download.DeviceDownload
import com.cleartune.app.library.LyricsUiState
import com.cleartune.core.designsystem.ClearTuneTheme
import com.cleartune.core.model.Song
import com.cleartune.core.player.PlaybackStatus
import com.cleartune.core.player.PlayerUiState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RecentUiChangesTest {
    @get:Rule val ui = createComposeRule()
    private val song = Song("ui-song", "测试歌曲", artistName = "测试歌手")

    @Test fun lyricsScreenReleasesScreenOnWhenPausedDisabledBackgroundedOrRemoved() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        var state by mutableStateOf(PlayerUiState(currentSong = song, status = PlaybackStatus.PLAYING))
        var enabled by mutableStateOf(true)
        var visible by mutableStateOf(true)
        lateinit var root: View
        ui.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        ui.setContent {
            root = LocalView.current.rootView
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                ClearTuneTheme {
                    if (visible) LyricsScreen(LyricsUiState(), state, {}, {}, {}, enabled)
                }
            }
        }
        fun assertScreenOn(expected: Boolean) {
            ui.waitForIdle()
            ui.runOnIdle { assertEquals(expected, hasScreenOn(root)) }
        }
        assertScreenOn(true)
        ui.runOnIdle { state = state.copy(status = PlaybackStatus.PAUSED) }
        assertScreenOn(false)
        ui.runOnIdle { state = state.copy(status = PlaybackStatus.PLAYING) }
        assertScreenOn(true)
        ui.runOnIdle { enabled = false }
        assertScreenOn(false)
        ui.runOnIdle { enabled = true; owner.registry.currentState = Lifecycle.State.CREATED }
        assertScreenOn(false)
        ui.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        assertScreenOn(true)
        ui.runOnIdle { visible = false }
        assertScreenOn(false)
    }

    private fun hasScreenOn(view: View): Boolean = view.keepScreenOn ||
        (view is ViewGroup && (0 until view.childCount).any { hasScreenOn(view.getChildAt(it)) })

    @Test fun downloadsKeepSecondaryActionsInMenuAndShowUpdateOnlyOnce() {
        var plays = 0
        var details = 0
        val item = DeviceDownload("id", "account", song, "content://test/song", 1024, "test.flac", needsUpdate = true)
        ui.setContent { ClearTuneTheme { Column {
            DeviceDownloadRow(item, false, false, { plays++ }, {}, {}, {}, {}, { details++ }, {})
        } } }
        ui.onNodeWithText("分享").assertDoesNotExist()
        ui.onNodeWithText("content://test/song").assertDoesNotExist()
        ui.onAllNodesWithText("有新版本").assertCountEquals(1)
        ui.onNodeWithContentDescription("播放").performClick()
        ui.runOnIdle { assertEquals(1, plays) }
        ui.onNodeWithContentDescription("更多操作").performClick()
        ui.onNodeWithText("分享").assertIsDisplayed()
        ui.onNodeWithText("重新定位").assertDoesNotExist()
        ui.onNodeWithText("文件信息").performClick()
        ui.runOnIdle { assertEquals(1, details) }
    }

    @Test fun unavailableDownloadOffersRelocationInsteadOfPlaybackOrShare() {
        var relocations = 0
        val item = DeviceDownload("id", "account", song, "content://missing", 1024, "test.flac", available = false)
        ui.setContent { ClearTuneTheme { Column {
            DeviceDownloadRow(item, false, false, {}, {}, {}, {}, { relocations++ }, {}, {})
        } } }
        ui.onNodeWithText("文件不可用").assertIsDisplayed()
        ui.onNodeWithContentDescription("播放").assertDoesNotExist()
        ui.onNodeWithContentDescription("更多操作").performClick()
        ui.onNodeWithText("分享").assertDoesNotExist()
        ui.onNodeWithText("重新定位").performClick()
        ui.runOnIdle { assertEquals(1, relocations) }
    }
}
