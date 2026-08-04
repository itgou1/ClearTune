package com.cleartune.app

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import com.cleartune.core.model.PlaylistCommand
import com.cleartune.core.model.TrackId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class AccessibilitySmokeTest {
    @get:Rule val composeRule = createComposeRule()
    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        container = AppContainer(ApplicationProvider.getApplicationContext<Context>())
    }

    private fun setProductContent(startDestination: String) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                ClearTuneApp(container, startDestination)
            }
        }
    }

    @After
    fun tearDown() = container.close()

    @Test
    fun primary_navigation_remains_actionable_at_200_percent_font_scale() {
        setProductContent(AppRoutes.Library)
        composeRule.onNodeWithContentDescription("Open settings")
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()

        composeRule.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test
    fun full_player_controls_are_scrollable_at_200_percent_font_scale() {
        setProductContent(AppRoutes.Player)

        composeRule.onNodeWithText("Previous").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Repeat off").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Queue 0").assertIsDisplayed()
    }

    @Test
    fun playlist_detail_actions_wrap_and_remain_visible_at_200_percent_font_scale() {
        val name = "Large type playlist ${System.nanoTime()}"
        val playlistId = runBlocking {
            container.playlistRepository.apply(PlaylistCommand.Create(name))
            val id = container.playlistRepository.observePlaylists().first()
                .first { it.name == name }.id
            container.playlistRepository.apply(PlaylistCommand.AddTrack(id, TrackId("large-type-track")))
            id
        }
        setProductContent(AppRoutes.playlistDetail(playlistId.value))

        try {
            composeRule.onNodeWithText(name).assertIsDisplayed()
            composeRule.onNodeWithText("Next").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("Last").assertIsDisplayed()
            composeRule.onNodeWithText("Remove").assertIsDisplayed()
        } finally {
            runBlocking { container.playlistRepository.apply(PlaylistCommand.Delete(playlistId)) }
        }
    }
}
