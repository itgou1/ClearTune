package com.cleartune.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.cleartune.app.auth.AccountSession
import com.cleartune.app.library.DetailUiState
import com.cleartune.app.library.MusicRepository
import com.cleartune.app.library.MusicViewModel
import com.cleartune.core.database.DatabaseFactory
import com.cleartune.core.datastore.AppPreferences
import com.cleartune.core.designsystem.ClearTuneTheme
import com.cleartune.core.model.*
import com.cleartune.core.network.OpenSubsonicApiFactory
import java.util.UUID
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SongListCoverTest {
    @get:Rule val ui = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun favoritesUseCoverFallbackInsteadOfNoteAndTrackNumber() = checkScreen(false)
    @Test fun playlistCoverOpensPlaybackAndLongPressSelects() = checkScreen(true)

    private fun checkScreen(playlist: Boolean) {
        val key = accountStorageKey("https://${UUID.randomUUID()}.example/", "cover-ui")
        val database = DatabaseFactory.create(context, key)
        val session = AccountSession(ServerCredentials("https://example.invalid/", "cover-ui", "test"),
            ServerProfile("https://example.invalid/", "cover-ui", "test", "1", "1.16.1", true), "ui", database)
        session.revoke() // An isolated rendering fixture; no real account or network requests.
        lateinit var viewModel: MusicViewModel
        val song = Song("a", "Cover test song", albumId = "album-a", artistName = "Artist",
            albumName = "Album", trackNumber = 17, starredAt = 1)
        var plays = 0
        ui.runOnIdle { viewModel = MusicViewModel(MusicRepository(session, OpenSubsonicApiFactory()), AppPreferences(context), session, context) }
        try {
            ui.setContent { ClearTuneTheme {
                if (playlist) PlaylistDetailScreen(
                    state = DetailUiState(songs = listOf(song)), viewModel = viewModel,
                    onBack = {}, onPlay = { _, _ -> plays++ }, onDownload = {}, allSongs = listOf(song),
                    onRename = { _, _ -> }, onRemoveSongs = { _, _ -> }, onDelete = { _, _ -> },
                    playlists = emptyList(), onPlayNext = {},
                ) else FavoriteSongsScreen(listOf(song), viewModel, FavoriteSongSort.TITLE.name,
                    {}, {}, { _, _ -> plays++ })
            } }
            val coverDescription = if (playlist) song.title else context.getString(R.string.song_album_cover, song.title)
            ui.onNodeWithContentDescription(coverDescription, useUnmergedTree = true).assertIsDisplayed()
            ui.onNodeWithText("♪", useUnmergedTree = true).assertDoesNotExist()
            ui.onNodeWithText("17", useUnmergedTree = true).assertDoesNotExist()
            ui.onNodeWithContentDescription(context.getString(
                if (playlist) R.string.more_actions else R.string.unlike_song)).assertIsDisplayed()
            ui.onNodeWithContentDescription(coverDescription, useUnmergedTree = true).performTouchInput { click() }
            ui.runOnIdle { assertEquals(1, plays) }
            if (playlist) {
                ui.onNodeWithContentDescription(coverDescription, useUnmergedTree = true).performTouchInput { longClick() }
                ui.onNode(hasText(song.title) and hasClickAction()).assertIsSelected()
                ui.onNodeWithContentDescription(context.getString(R.string.more_actions)).assertDoesNotExist()
                ui.onNodeWithContentDescription(context.getString(R.string.unlike_song)).assertDoesNotExist()
                ui.onNode(hasText(song.title) and hasClickAction()).performClick()
                ui.onNode(hasText(song.title) and hasClickAction()).assertIsNotSelected()
                ui.runOnIdle { assertEquals(1, plays) }
            }
        } finally {
            ui.runOnIdle { viewModel.viewModelScope.cancel() }
            database.close()
            context.deleteDatabase("cleartune_account_$key.db")
        }
    }

    @Test fun largeTextKeepsCoverAndFavoriteVisible() {
        ui.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.5f)) {
                ClearTuneTheme(darkTheme = true) { Column(Modifier.width(320.dp)) {
                    SongRow(Song("a", "这是一首名字比较长的收藏歌曲", artistName = "音乐人", albumName = "专辑"),
                        onClick = {}, onFavorite = {}, leadingArtwork = {
                            Box(Modifier.fillMaxSize().background(Color.Gray).testTag("cover"))
                        })
                } }
            }
        }
        ui.onNodeWithTag("cover", useUnmergedTree = true).assertIsDisplayed()
            .assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        ui.onNodeWithContentDescription(context.getString(R.string.like_song)).assertIsDisplayed()
    }
}
