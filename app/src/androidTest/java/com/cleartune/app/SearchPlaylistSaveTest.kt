package com.cleartune.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.cleartune.app.library.MusicViewModel
import com.cleartune.app.library.SearchUiState
import com.cleartune.core.datastore.AppPreferences
import com.cleartune.core.designsystem.ClearTuneTheme
import com.cleartune.core.model.Playlist
import com.cleartune.core.model.Song
import com.cleartune.core.network.SearchResults
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SearchPlaylistSaveTest {
    @get:Rule val ui = createComposeRule()

    @Test fun searchSelectionSavesExactSongAndRefreshShowsIt() = check("search-song") { fixture, message ->
        assertEquals("已添加到歌单", message)
        assertEquals(listOf("search-song"), fixture.mutations.single().getQueryParameters("songIdToAdd"))
        runBlocking {
            assertNull(fixture.repository.loadPlaylist("p"))
            assertEquals(listOf("existing", "search-song"), fixture.repository.playlistSongs("p").first().map(Song::id))
        }
    }

    @Test fun existingSearchSongExplainsThatNoNewSongWasAdded() = check("existing") { fixture, message ->
        assertEquals("歌曲已在歌单中", message)
        assertTrue(fixture.mutations.isEmpty())
    }

    @Test fun ignoredSearchSongDoesNotShowSuccess() = check("ignored", ignore = true) { _, message ->
        assertTrue(message.startsWith("歌曲未加入歌单"))
    }

    private fun check(id: String, ignore: Boolean = false, verify: (BatchPlaylistRepositoryTest.Fixture, String) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        BatchPlaylistRepositoryTest.Fixture().use { fixture ->
            runBlocking { fixture.seedPlaylist() }
            if (ignore) fixture.ignoredIds = setOf(id)
            lateinit var model: MusicViewModel
            ui.runOnIdle { model = MusicViewModel(fixture.repository, AppPreferences(context), fixture.session, context) }
            try {
                ui.setContent {
                    ClearTuneTheme {
                        SearchScreen(
                            state = SearchUiState(query = "眉间雪", results = SearchResults(emptyList(), emptyList(),
                                listOf(Song(id, "眉间雪（纯歌版）", artistName = "晴愔")))),
                            recentSearches = emptyList(), genres = emptyList(),
                            playlists = listOf(Playlist("p", "偶尔听听")), viewModel = model,
                            onAlbum = {}, onArtist = {}, onPlaylist = {}, onPlay = { _, _ -> },
                            onPlayNext = {}, onDownload = {},
                        )
                    }
                }
                ui.onNodeWithContentDescription(context.getString(R.string.more_actions)).performClick()
                ui.onNodeWithText(context.getString(R.string.add_to_playlist)).performClick()
                ui.onNodeWithText("偶尔听听").performClick()
                ui.waitUntil(10_000) { model.actionMessage.value != null }
                verify(fixture, requireNotNull(model.actionMessage.value))
            } finally {
                ui.runOnIdle { model.viewModelScope.cancel() }
            }
        }
    }
}
