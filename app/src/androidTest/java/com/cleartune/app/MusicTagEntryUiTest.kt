package com.cleartune.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.cleartune.app.auth.AccountSession
import com.cleartune.app.library.*
import com.cleartune.app.metadata.LocalMusicTagActions
import com.cleartune.app.metadata.MusicTagActions
import com.cleartune.app.player.PlaybackRepository
import com.cleartune.app.player.PlayerViewModel
import com.cleartune.core.database.DatabaseFactory
import com.cleartune.core.datastore.AppPreferences
import com.cleartune.core.datastore.PlaybackPreferences
import com.cleartune.core.designsystem.ClearTuneTheme
import com.cleartune.core.model.*
import com.cleartune.core.network.OpenSubsonicApiFactory
import com.cleartune.core.player.PlayerUiState
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MusicTagEntryUiTest {
    @get:Rule val ui = createComposeRule()
    @Test fun libraryMoreOpensTagsForThatSong() = checkEntry(false)
    @Test fun playerMoreOpensTagsForCurrentSong() = checkEntry(true)

    private fun checkEntry(playerScreen: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = accountStorageKey("https://${UUID.randomUUID()}.example/", "tag-test")
        val database = DatabaseFactory.create(context, key)
        val session = AccountSession(ServerCredentials("https://example.invalid/", "tag-test", "test"),
            ServerProfile("https://example.invalid/", "tag-test", "test", "1", "1.16.1", true), "ui", database)
        session.revoke()
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
                MusicViewModel::class.java -> MusicViewModel(MusicRepository(session, OpenSubsonicApiFactory()), AppPreferences(context), session, context)
                else -> PlayerViewModel(context, PlaybackRepository(context, session, OpenSubsonicApiFactory(), AppPreferences(context)), PlaybackPreferences(context))
            } as T
        }
        lateinit var music: MusicViewModel
        lateinit var player: PlayerViewModel
        ui.runOnIdle {
            val provider = ViewModelProvider(store, factory)
            music = provider[MusicViewModel::class.java]
            player = provider[PlayerViewModel::class.java]
        }
        val song = Song("tag-song", "Tag UI fixture", artistName = "Test artist")
        var opened: Song? = null
        try {
            ui.setContent {
                ClearTuneTheme {
                    CompositionLocalProvider(LocalMusicTagActions provides MusicTagActions(open = { opened = it })) {
                        if (playerScreen) NowPlayingScreen(PlayerUiState(currentSong = song), player, music,
                            LyricsUiState(), false, {}, {}, {}, { _, _ -> }, {})
                        else LibraryScreen(SongBatchSelection(), LibraryUiState(songs = listOf(song), isInitializing = false),
                            emptyList(), FolderUiState(), music, {}, {}, {}, { _, _ -> }, {}, {}, {}, {})
                    }
                }
            }
            if (!playerScreen) ui.onNodeWithText(context.getString(R.string.songs)).performClick()
            ui.onNodeWithContentDescription(context.getString(R.string.more_actions)).performClick()
            if (playerScreen) {
                ui.onNodeWithText(context.getString(R.string.playback_mode)).assertDoesNotExist()
                val heading = ui.onNodeWithText(context.getString(R.string.more_actions)).fetchSemanticsNode().boundsInRoot
                val tag = ui.onNodeWithText("音乐标签").fetchSemanticsNode().boundsInRoot
                assertTrue("Menu heading should precede music tags", heading.bottom <= tag.top)
            }
            ui.onNodeWithText("音乐标签").assertIsDisplayed().performClick()
            ui.runOnIdle { assertEquals(song.id, opened?.id) }
        } finally {
            ui.runOnIdle { store.clear() }
            database.close()
        }
    }
}
