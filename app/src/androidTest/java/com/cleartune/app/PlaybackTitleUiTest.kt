package com.cleartune.app

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cleartune.core.designsystem.ClearTuneTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackTitleUiTest {
    @get:Rule val ui = createComposeRule()

    @Test fun longTitleWaitsFreezesResumesAndResetsOnSongChange() {
        val playing = mutableStateOf(false)
        val songId = mutableStateOf("first")
        ui.mainClock.autoAdvance = false
        ui.setContent {
            ClearTuneTheme {
                PlaybackTitle(
                    "放过今天的我吧，我真的想歇歇 — 一首完整名称很长的歌曲",
                    songId.value, playing.value, Modifier.width(160.dp).testTag("title"),
                )
            }
        }
        repeat(3) { ui.mainClock.advanceTimeByFrame() }
        fun picture() = ui.onNodeWithTag("title").captureToImage().asAndroidBitmap()
        val beginning = picture()
        ui.mainClock.advanceTimeBy(4_000)
        assertTrue("Paused title must stay at start", beginning.sameAs(picture()))
        ui.runOnIdle { playing.value = true }
        ui.mainClock.advanceTimeBy(1_000)
        assertTrue("Initial hold must apply", beginning.sameAs(picture()))
        ui.mainClock.advanceTimeBy(3_000)
        assertFalse("Overflowing title must move during playback", beginning.sameAs(picture()))
        ui.runOnIdle { playing.value = false }
        ui.mainClock.advanceTimeByFrame()
        val paused = picture()
        ui.mainClock.advanceTimeBy(5_000)
        assertTrue("Pause must freeze the current position", paused.sameAs(picture()))
        ui.runOnIdle { playing.value = true }
        ui.mainClock.advanceTimeBy(1_000)
        assertFalse("Resume must continue without another initial hold", paused.sameAs(picture()))
        ui.runOnIdle { songId.value = "second" }
        repeat(3) { ui.mainClock.advanceTimeByFrame() }
        assertTrue("Another song with the same title must restart", beginning.sameAs(picture()))
    }

    @Test fun shortTitleDoesNotMoveDuringPlayback() {
        ui.mainClock.autoAdvance = false
        ui.setContent {
            ClearTuneTheme {
                PlaybackTitle("短歌名", "short", true, Modifier.width(160.dp).testTag("title"))
            }
        }
        repeat(3) { ui.mainClock.advanceTimeByFrame() }
        val initial = ui.onNodeWithTag("title").captureToImage().asAndroidBitmap()
        ui.mainClock.advanceTimeBy(8_000)
        assertTrue(initial.sameAs(ui.onNodeWithTag("title").captureToImage().asAndroidBitmap()))
    }
}
