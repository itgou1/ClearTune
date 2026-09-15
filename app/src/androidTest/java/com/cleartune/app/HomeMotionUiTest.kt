package com.cleartune.app

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeMotionUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun entranceWaitsForContentAndDoesNotReplayOnReturnOrRestore() {
        compose.mainClock.autoAdvance = false
        var ready by mutableStateOf(false)
        var showHome by mutableStateOf(true)
        var starts = 0
        lateinit var progress: State<Float>
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            var played by rememberSaveable { mutableStateOf(false) }
            if (showHome) {
                progress = rememberHomeEntranceProgress(ready, !played) { played = true; starts++ }
            }
        }
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle { assertEquals(0, starts); ready = true }
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle {
            assertEquals(1, starts)
            assertEquals(1f, progress.value, 0f)
            showHome = false
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { showHome = true }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals(1f, progress.value, 0f); assertEquals(1, starts) }
        restoration.emulateSavedInstanceStateRestore()
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals(1f, progress.value, 0f); assertEquals(1, starts) }
    }

    @Test fun leavingMidEntranceDoesNotReplayOnReturn() {
        compose.mainClock.autoAdvance = false
        var showHome by mutableStateOf(true)
        var starts = 0
        lateinit var progress: State<Float>
        compose.setContent {
            var played by rememberSaveable { mutableStateOf(false) }
            if (showHome) progress = rememberHomeEntranceProgress(true, !played) { played = true; starts++ }
        }
        compose.mainClock.advanceTimeBy(96)
        compose.runOnIdle {
            assertTrue(progress.value < 1f)
            showHome = false
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { showHome = true }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals(1f, progress.value, 0f); assertEquals(1, starts) }
    }

    @Test fun pressShrinksAndScrollCancellationRestoresScale() {
        compose.mainClock.autoAdvance = false
        val interactions = MutableInteractionSource()
        lateinit var scale: State<Float>
        compose.setContent { scale = rememberHomePressScale(interactions) }
        val press = PressInteraction.Press(Offset.Zero)
        compose.runOnIdle { interactions.tryEmit(press) }
        compose.mainClock.advanceTimeBy(200)
        compose.runOnIdle { assertEquals(0.975f, scale.value, 0.001f) }
        compose.runOnIdle { interactions.tryEmit(PressInteraction.Cancel(press)) }
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { assertEquals(1f, scale.value, 0.001f) }
    }

    @Test fun disabledCardNeverShrinks() {
        compose.mainClock.autoAdvance = false
        val interactions = MutableInteractionSource()
        lateinit var scale: State<Float>
        compose.setContent { scale = rememberHomePressScale(interactions, enabled = false) }
        compose.runOnIdle { interactions.tryEmit(PressInteraction.Press(Offset.Zero)) }
        compose.mainClock.advanceTimeBy(200)
        compose.runOnIdle { assertEquals(1f, scale.value, 0f) }
    }
}
