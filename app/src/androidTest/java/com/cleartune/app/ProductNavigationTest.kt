package com.cleartune.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cleartune.core.model.PlaylistCommand
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

@Suppress("DEPRECATION")
class ProductNavigationTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun settings_destination_restores_after_activity_recreation_and_supports_back() {
        composeRule.onNodeWithContentDescription("Open settings").performClick()
        composeRule.onNodeWithText("Settings").assertIsDisplayed()

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Settings").assertIsDisplayed()

        composeRule.onNodeWithText("Back").performClick()
        composeRule.onNodeWithContentDescription("Open settings").assertIsDisplayed()
    }

    @Test
    fun playlist_detail_restores_after_activity_recreation_and_back_returns_to_list() {
        val repository = (composeRule.activity.application as ClearTuneApplication).container.playlistRepository
        val name = "Recreated playlist ${System.nanoTime()}"
        val playlistId = runBlocking {
            repository.apply(PlaylistCommand.Create(name))
            repository.observePlaylists().first().first { it.name == name }.id
        }
        try {
            composeRule.onNodeWithContentDescription("Open playlists").performClick()
            composeRule.onNodeWithText(name).performClick()
            composeRule.onNodeWithText(name).assertIsDisplayed()

            composeRule.activityRule.scenario.recreate()
            composeRule.waitForIdle()
            composeRule.onNodeWithText(name).assertIsDisplayed()

            composeRule.onNodeWithText("Back").performClick()
            composeRule.onNodeWithText("Playlists").assertIsDisplayed()
            composeRule.onNodeWithText(name).assertIsDisplayed()
        } finally {
            runBlocking { repository.apply(PlaylistCommand.Delete(playlistId)) }
        }
    }
}
