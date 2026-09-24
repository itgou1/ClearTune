package com.cleartune.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.cleartune.app.auth.AccountSession
import com.cleartune.app.library.*
import com.cleartune.core.database.DatabaseFactory
import com.cleartune.core.datastore.AppPreferences
import com.cleartune.core.designsystem.ClearTuneTheme
import com.cleartune.core.model.*
import com.cleartune.core.network.OpenSubsonicApiFactory
import java.util.UUID
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SongBatchSelectionUiTest {
    @get:Rule val ui = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val songs = listOf(Song("a", "Batch Alpha"), Song("b", "Batch Beta"))

    @Test fun libraryLongPressSelectsWithoutPlaying() = checkScreen("library")
    @Test fun albumLongPressSelectsWithoutPlaying() = checkScreen("album")
    @Test fun artistLongPressSelectsWithoutPlaying() = checkScreen("artist")

    private fun checkScreen(screen: String) {
        val key = accountStorageKey("https://${UUID.randomUUID()}.example/", "batch-ui")
        val database = DatabaseFactory.create(context, key)
        val session = AccountSession(ServerCredentials("https://example.invalid/", "batch-ui", "test"),
            ServerProfile("https://example.invalid/", "batch-ui", "test", "1", "1.16.1", true), "ui", database)
        session.revoke() // Rendering fixture must never make authenticated network requests.
        lateinit var viewModel: MusicViewModel
        val selection = SongBatchSelection()
        var plays = 0
        ui.runOnIdle { viewModel = MusicViewModel(MusicRepository(session, OpenSubsonicApiFactory()), AppPreferences(context), session, context) }
        try {
            ui.setContent {
                ClearTuneTheme {
                    when (screen) {
                        "library" -> LibraryScreen(selection, LibraryUiState(songs = songs, isInitializing = false),
                            emptyList(), FolderUiState(), viewModel, {}, {}, {}, { _, _ -> plays++ }, {}, {}, {}, {})
                        "album" -> AlbumDetailScreen(selection, DetailUiState(songs = songs), emptyList(), viewModel,
                            {}, { _, _ -> plays++ }, {}, {})
                        else -> ArtistDetailScreen(selection, DetailUiState(songs = songs), emptyList(), emptyList(), viewModel,
                            {}, {}, { _, _ -> plays++ }, {}, {}, {})
                    }
                }
            }
            if (screen == "library") ui.onNodeWithText(context.getString(R.string.songs)).performClick()
            ui.onNode(hasText("Batch Alpha") and hasClickAction()).performClick()
            ui.runOnIdle { assertEquals(1, plays) }
            ui.onNode(hasText("Batch Alpha") and hasClickAction()).performTouchInput { longClick() }
            ui.runOnIdle {
                assertTrue(selection.active)
                assertEquals(setOf("a"), selection.ids)
                assertEquals(1, plays)
            }
            ui.onNode(hasText("Batch Beta") and hasClickAction()).performClick()
            ui.runOnIdle { assertEquals(setOf("a", "b"), selection.ids); assertEquals(1, plays) }
            ui.onNodeWithText(context.getString(R.string.clear_selection)).performClick()
            ui.runOnIdle { assertTrue(selection.ids.isEmpty()); assertTrue(selection.active) }
            ui.onNodeWithText(context.getString(R.string.select_all)).performClick()
            ui.runOnIdle { assertEquals(2, selection.ids.size) }
            ui.onNodeWithContentDescription(context.getString(R.string.add_to_playlist)).assertDoesNotExist()
            ui.onNodeWithContentDescription(context.getString(R.string.more_actions)).assertDoesNotExist()
            ui.onNodeWithText(context.getString(R.string.cancel)).performClick()
            ui.runOnIdle { assertFalse(selection.active); assertTrue(selection.ids.isEmpty()) }
        } finally {
            ui.runOnIdle { viewModel.viewModelScope.cancel() }
            database.close()
            context.deleteDatabase("cleartune_account_$key.db")
        }
    }

    @Test fun longPressOnFavoriteDoesNotEnterSelection() {
        var selected = false
        var favorites = 0
        ui.setContent { ClearTuneTheme { Column {
            SongRow(songs.first(), onClick = {}, onLongClick = { selected = true }, onFavorite = { favorites++ })
        } } }
        ui.onNodeWithContentDescription(context.getString(R.string.like_song)).performTouchInput { longClick() }
        ui.runOnIdle { assertFalse(selected) }
    }

    @Test fun emptySelectionDisablesBottomAction() {
        ui.setContent { ClearTuneTheme { SongSelectionBottomBar(0, false) {} } }
        ui.onNodeWithText(context.getString(R.string.batch_add_count, 0)).assertIsNotEnabled()
    }

    @Test fun failedSheetRetainsChoiceAndCanRetryOrCreatePlaylist() {
        var target: String? = null
        var newName: String? = null
        ui.setContent { ClearTuneTheme {
            BatchPlaylistSheet(listOf(Playlist("p", "Target playlist", 4)), 2, false, "Test failure",
                onSelect = { target = it }, onCreate = { newName = it }, onDismiss = {},
                cover = { Box(Modifier.size(44.dp)) })
        } }
        ui.onNodeWithText("Test failure").assertIsDisplayed()
        ui.onNodeWithText("Target playlist").performClick()
        ui.runOnIdle { assertEquals("p", target) }
        ui.onNodeWithText(context.getString(R.string.batch_new_playlist)).performClick()
        ui.onNode(hasSetTextAction()).performTextInput("New list")
        ui.onNodeWithText(context.getString(R.string.batch_create_and_add)).performClick()
        ui.runOnIdle { assertEquals("New list", newName) }
    }
}
