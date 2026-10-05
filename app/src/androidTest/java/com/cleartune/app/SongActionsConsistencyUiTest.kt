package com.cleartune.app

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.cleartune.app.auth.AccountSession
import com.cleartune.app.library.*
import com.cleartune.app.metadata.LocalMusicTagActions
import com.cleartune.app.metadata.MusicTagActions
import com.cleartune.core.database.DatabaseFactory
import com.cleartune.core.datastore.AppPreferences
import com.cleartune.core.designsystem.ClearTuneTheme
import com.cleartune.core.model.*
import com.cleartune.core.network.OpenSubsonicApiFactory
import com.cleartune.core.network.SearchResults
import java.util.UUID
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SongActionsConsistencyUiTest {
    @get:Rule val ui = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val songs = listOf(
        Song("alpha", "Alpha fixture", trackNumber = 1),
        Song("beta", "Beta fixture", trackNumber = 2, starredAt = 1L,
            suffix = "flac", createdAt = System.currentTimeMillis()),
    )

    @Test fun libraryMenuTargetsSelectedSong() = checkScreen("library")
    @Test fun searchMenuTargetsSelectedSongWithoutMultiSelect() = checkScreen("search")
    @Test fun albumMenuTargetsSelectedSongAndKeepsTrackAndBadge() = checkScreen("album")
    @Test fun artistMenuTargetsSelectedSong() = checkScreen("artist")
    @Test fun playlistMenuTargetsSelectedSong() = checkScreen("playlist")

    @Test fun playlistSortAppliesToPlaybackAndRemovalAndSurvivesPageReentry() = withModel { model ->
        val id = "sort-${UUID.randomUUID()}"
        var visible by mutableStateOf(true)
        var queue = emptyList<String>()
        var removed = emptyList<Int>()
        ui.setContent {
            ClearTuneTheme {
                if (visible) PlaylistDetailScreen(
                    state = DetailUiState(playlist = Playlist(id, "Sorting fixture", 2), songs = songs),
                    viewModel = model, onBack = {}, onPlay = { list, _ -> queue = list.map { it.id } },
                    onDownload = {}, allSongs = songs, playlists = emptyList(), onPlayNext = {},
                    onRename = { _, _ -> }, onRemoveSongs = { _, indexes -> removed = indexes },
                    onDelete = { _, _ -> },
                )
            }
        }
        ui.waitUntil(5_000) { ui.onAllNodes(hasContentDescription("排序") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithContentDescription("排序").performClick()
        ui.onNodeWithText("歌曲名").performClick()
        ui.onNodeWithText("歌曲名").assertDoesNotExist()
        ui.onNodeWithContentDescription("排序").performClick()
        ui.onNodeWithText("歌曲名").performClick()
        ui.waitForIdle()
        ui.onNodeWithContentDescription("排序").performClick()
        ui.onNodeWithText("歌曲名").assertIsDisplayed()
        ui.waitForIdle()
        Thread.sleep(300) // Allow the popup window to draw before the device screenshot.
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(context.getExternalFilesDir(null), "playlist-sort-menu.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        ui.onNode(isPopup()).performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Escape) }
        ui.waitUntil(5_000) {
            kotlinx.coroutines.runBlocking { model.playlistSort(id).first() }.descending
        }
        ui.onNodeWithText(context.getString(R.string.play_all)).performClick()
        ui.runOnIdle { assertEquals(listOf("beta", "alpha"), queue); visible = false }
        ui.waitForIdle()
        ui.runOnIdle { visible = true }
        ui.waitUntil(5_000) {
            ui.onAllNodes(hasText(songs[1].title) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithContentDescription("排序").performClick()
        ui.onNodeWithText("歌曲名").assertIsSelected()
        ui.onNodeWithText("歌曲名").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "降序"))
        ui.onNode(isPopup()).performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Escape) }
        ui.onNode(hasText(songs[1].title) and hasClickAction()).performScrollTo().performTouchInput { longClick() }
        ui.onNodeWithText(context.getString(R.string.remove_from_playlist)).performClick()
        ui.onNode(hasText(context.getString(R.string.remove_action)) and hasAnyAncestor(isDialog())).performClick()
        ui.runOnIdle { assertEquals(listOf(1), removed) }
    }

    private fun checkScreen(screen: String) = withModel { model ->
        val selection = SongBatchSelection()
        val next = mutableListOf<String>()
        val downloads = mutableListOf<String>()
        val shares = mutableListOf<String>()
        val tags = mutableListOf<String>()
        var played: List<String>? = null
        var playedIndex = -1
        val play: (List<Song>, Int) -> Unit = { queue, index ->
            played = queue.map(Song::id)
            playedIndex = index
        }
        ui.setContent {
            ClearTuneTheme {
                CompositionLocalProvider(LocalMusicTagActions provides MusicTagActions(open = { tags += it.id })) {
                    when (screen) {
                        "search" -> SearchScreen(
                            state = SearchUiState(query = "fixture", results = SearchResults(emptyList(), emptyList(), songs)),
                            recentSearches = emptyList(), genres = emptyList(), playlists = emptyList(),
                            viewModel = model, onAlbum = {}, onArtist = {}, onPlaylist = {},
                            onPlay = play, onPlayNext = { next += it.id },
                            onDownload = { downloads += it.id }, onShare = { shares += it.id },
                        )
                        "album" -> AlbumDetailScreen(
                            selection, DetailUiState(songs = songs), emptyList(), model, {}, play,
                            onDownload = { downloads += it.map(Song::id) },
                            onPlayNext = { next += it.id }, onShare = { shares += it.id },
                        )
                        "artist" -> ArtistDetailScreen(
                            selection, DetailUiState(songs = songs), emptyList(), emptyList(), model, {}, {}, play,
                            onDownload = { downloads += it.map(Song::id) },
                            onPlayNext = { next += it.id }, onShare = { shares += it.id },
                        )
                        "playlist" -> PlaylistDetailScreen(
                            state = DetailUiState(songs = songs), viewModel = model,
                            onBack = {}, onPlay = play, onDownload = { downloads += it.map(Song::id) },
                            allSongs = songs, playlists = emptyList(),
                            onPlayNext = { next += it.id }, onShare = { shares += it.id },
                            onRename = { _, _ -> }, onRemoveSongs = { _, _ -> }, onDelete = { _, _ -> },
                        )
                        else -> LibraryScreen(
                            selection, LibraryUiState(songs = songs, isInitializing = false),
                            emptyList(), FolderUiState(), model, {}, {}, {}, play,
                            onPlayNext = { next += it.id }, onDownload = { downloads += it.id },
                            onShare = { shares += it.id }, onGenre = {},
                        )
                    }
                }
            }
        }
        if (screen == "library") ui.onNodeWithText(context.getString(R.string.songs)).performClick()
        if (screen == "search") {
            ui.onNode(hasText("Beta fixture") and hasClickAction())
                .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
        }
        if (screen == "album") {
            ui.onNodeWithText("2", useUnmergedTree = true).assertIsDisplayed()
            ui.onNodeWithContentDescription(context.getString(R.string.song_recently_added_description),
                useUnmergedTree = true).assertIsDisplayed()
        }
        fun openMenu() {
            ui.onAllNodesWithContentDescription(context.getString(R.string.more_actions))[1].performClick()
        }
        fun menuItem(label: String) = ui.onNode(hasText(label) and hasAnyAncestor(isPopup()))
        openMenu()
        if (screen == "playlist") menuItem(context.getString(R.string.add_to_playlist)).assertDoesNotExist()
        val labels = (if (screen == "playlist") emptyList() else listOf(context.getString(R.string.add_to_playlist))) + listOf(
            context.getString(R.string.unlike_song),
            context.getString(R.string.play_next), context.getString(R.string.download_action),
            "分享歌曲", "音乐标签", context.getString(R.string.song_details),
        )
        val tops = labels.map { label ->
            menuItem(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        }
        assertTrue("Menu order on $screen", tops.zipWithNext().all { (a, b) -> a < b })
        menuItem(context.getString(R.string.play_next)).performClick()
        openMenu()
        menuItem(context.getString(R.string.download_action)).performClick()
        openMenu()
        menuItem("分享歌曲").performClick()
        openMenu()
        menuItem("音乐标签").performClick()
        openMenu()
        menuItem(context.getString(R.string.song_details)).performClick()
        ui.onNode(hasText("Beta fixture") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        ui.onNodeWithText(context.getString(R.string.close_action)).performClick()
        ui.runOnIdle {
            assertEquals(listOf("beta"), next)
            assertEquals(listOf("beta"), downloads)
            assertEquals(listOf("beta"), shares)
            assertEquals(listOf("beta"), tags)
            assertNull("Opening and using the menu must not start playback", played)
            assertFalse(selection.active)
        }
        ui.onNode(hasText("Beta fixture") and hasClickAction()).performClick()
        ui.runOnIdle {
            assertEquals(listOf("alpha", "beta"), played)
            assertEquals(1, playedIndex)
        }
    }

    @Test fun likeStateAndPlaylistSelectionWorkThroughSharedMenu() = withModel { model ->
        var song by mutableStateOf(songs.first())
        var target: String? = null
        ui.setContent {
            ClearTuneTheme { Column {
                SongActionsMenu(song, listOf(Playlist("target", "Target playlist", 1)), model,
                    onAddToPlaylist = { target = it },
                    onToggleLike = { song = song.copy(starredAt = if (song.starredAt == null) 1L else null) },
                    onPlayNext = {}, onDownload = {}, onShare = {})
            } }
        }
        fun openMenu() = ui.onNodeWithContentDescription(context.getString(R.string.more_actions)).performClick()
        openMenu()
        ui.onNodeWithText(context.getString(R.string.like_song)).performClick()
        openMenu()
        ui.onNodeWithText(context.getString(R.string.unlike_song)).performClick()
        ui.runOnIdle { assertNull(song.starredAt) }
        openMenu()
        ui.onNodeWithText(context.getString(R.string.add_to_playlist)).performClick()
        ui.onNodeWithText("Target playlist").performClick()
        ui.runOnIdle { assertEquals("target", target) }
        ui.onNodeWithText("Target playlist").assertDoesNotExist()
    }

    @Test fun playlistLongPressRemovesOnlySelectedOccurrenceOfDuplicateSong() = withModel { model ->
        val repeated = songs.first()
        var plays = 0
        var removed: Pair<String, List<Int>>? = null
        ui.setContent {
            ClearTuneTheme {
                PlaylistDetailScreen(
                    state = DetailUiState(playlist = Playlist("playlist", "Test playlist", 2),
                        songs = listOf(repeated, repeated)),
                    viewModel = model, onBack = {}, onPlay = { _, _ -> plays++ }, onDownload = {},
                    allSongs = songs, playlists = emptyList(), onPlayNext = {},
                    onRename = { _, _ -> }, onRemoveSongs = { id, indexes -> removed = id to indexes },
                    onDelete = { _, _ -> },
                )
            }
        }
        val rows = ui.onAllNodes(hasText(repeated.title) and hasClickAction())
        rows[1].performScrollTo().performTouchInput { longClick() }
        rows[0].assertIsNotSelected()
        rows[1].assertIsSelected()
        ui.onNodeWithContentDescription(context.getString(R.string.more_actions)).assertDoesNotExist()
        ui.onNodeWithText(context.getString(R.string.select_all)).performClick()
        rows[0].assertIsSelected()
        rows[1].assertIsSelected()
        ui.onNodeWithText(context.getString(R.string.clear_selection)).performClick()
        rows[0].assertIsNotSelected()
        rows[1].assertIsNotSelected()
        ui.onNodeWithText(context.getString(R.string.remove_from_playlist)).assertIsNotEnabled()
        rows[1].performClick()
        ui.onNodeWithText(context.getString(R.string.remove_from_playlist)).performClick()
        ui.onNode(hasText(context.getString(R.string.remove_action)) and hasAnyAncestor(isDialog())).performClick()
        ui.runOnIdle {
            assertEquals("playlist" to listOf(1), removed)
            assertEquals(0, plays)
        }
        ui.onNodeWithText(context.getString(R.string.remove_from_playlist)).assertDoesNotExist()
    }

    private fun withModel(check: (MusicViewModel) -> Unit) {
        val key = accountStorageKey("https://${UUID.randomUUID()}.example/", "song-menu-ui")
        val database = DatabaseFactory.create(context, key)
        val session = AccountSession(ServerCredentials("https://example.invalid/", "song-menu-ui", "test"),
            ServerProfile("https://example.invalid/", "song-menu-ui", "test", "1", "1.16.1", true), "ui", database)
        session.revoke()
        lateinit var model: MusicViewModel
        ui.runOnIdle { model = MusicViewModel(MusicRepository(session, OpenSubsonicApiFactory()),
            AppPreferences(context), session, context) }
        try { check(model) } finally {
            ui.runOnIdle { model.viewModelScope.cancel() }
            database.close()
            context.deleteDatabase("cleartune_account_$key.db")
        }
    }
}
