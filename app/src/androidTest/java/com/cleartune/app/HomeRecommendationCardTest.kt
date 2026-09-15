package com.cleartune.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cleartune.core.designsystem.ClearTuneTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeRecommendationCardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun wholeCardHasOnePlaybackAction() {
        var plays = 0
        compose.setContent {
            ClearTuneTheme {
                HomeRecommendationCard(
                    title = "随便听听", description = "从曲库随机挑选",
                    enabled = true, hasRearCover = true, onPlay = { plays++ },
                    modifier = Modifier.width(180.dp).testTag("card"),
                    artwork = { _, modifier -> Box(modifier.background(Color.Gray)) },
                )
            }
        }
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        compose.onNodeWithTag("card").performClick()
        compose.runOnIdle { assertEquals(1, plays) }
    }

    @Test fun emptyCardCannotStartPlayback() {
        var plays = 0
        compose.setContent {
            ClearTuneTheme {
                HomeRecommendationCard(
                    title = "常听精选", description = "还没有足够的播放记录",
                    enabled = false, hasRearCover = false, onPlay = { plays++ },
                    modifier = Modifier.width(180.dp).testTag("card"),
                    artwork = { _, modifier -> Box(modifier.background(Color.Gray)) },
                )
            }
        }
        compose.onNodeWithTag("card").assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, plays) }
    }

    @Test fun narrowCardWithLargeTextKeepsFrontCoverInsideCard() {
        var card = Rect.Zero
        var front = Rect.Zero
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.6f)) {
                ClearTuneTheme(darkTheme = true) {
                    HomeRecommendationCard(
                        title = "随便听听", description = "从曲库随机挑选",
                        enabled = true, hasRearCover = true, onPlay = {},
                        modifier = Modifier.width(144.dp).testTag("card")
                            .onGloballyPositioned { card = it.boundsInRoot() },
                        artwork = { index, modifier ->
                            Box(modifier.background(Color.Gray).onGloballyPositioned {
                                if (index == 0) front = it.boundsInRoot()
                            })
                        },
                    )
                }
            }
        }
        compose.onNodeWithText("随便听听").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(front.width > 0f)
            assertTrue(front.left >= card.left && front.right <= card.right)
            assertTrue(front.top >= card.top && front.bottom <= card.bottom)
            assertEquals(front.width, front.height, 1f)
        }
    }

    @Test fun singleCoverDoesNotRequestSecondSong() {
        val requestedIndexes = mutableSetOf<Int>()
        compose.setContent {
            ClearTuneTheme {
                HomeRecommendationCard(
                    title = "常听精选", description = "重温熟悉的旋律",
                    enabled = true, hasRearCover = false, onPlay = {},
                    modifier = Modifier.width(180.dp),
                    artwork = { index, modifier ->
                        requestedIndexes += index
                        Box(modifier.background(Color.Gray))
                    },
                )
            }
        }
        compose.runOnIdle { assertEquals(setOf(0), requestedIndexes) }
    }
}
