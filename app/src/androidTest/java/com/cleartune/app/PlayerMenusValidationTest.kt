package com.cleartune.app

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.cleartune.app.auth.AccountSession
import com.cleartune.app.library.*
import com.cleartune.app.player.*
import com.cleartune.core.database.DatabaseFactory
import com.cleartune.core.database.toEntity
import com.cleartune.core.datastore.AppPreferences
import com.cleartune.core.datastore.PlaybackPreferences
import com.cleartune.core.designsystem.ClearTuneTheme
import com.cleartune.core.model.*
import com.cleartune.core.network.OpenSubsonicApiFactory
import com.cleartune.core.player.PlayerUiState
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlayerMenusValidationTest {
    @get:Rule val ui = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val first = Song("first", "菜单验证甲", artistId = "artist", artistName = "测试歌手", albumId = "album", albumName = "测试专辑")
    private val second = first.copy(id = "second", title = "菜单验证乙", starredAt = 1L)
    private val state = PlayerUiState(currentSong = first, currentIndex = 0, queue = listOf(first, second))

    @Test fun mainMenuHasCorrectOrderAndAlbumNavigation() = models { music, player ->
        var album: String? = null
        ui.setContent { ClearTuneTheme { NowPlayingScreen(state, player, music, LyricsUiState(), false,
            {}, {}, {}, { _, _ -> }, {}, onAlbum = { album = it }) } }
        ui.onNodeWithContentDescription(context.getString(R.string.more_actions)).performClick()
        order(listOf("添加到歌单", "查看专辑", "查看歌手", "下载歌曲", "分享歌曲", "音乐标签", "歌曲详情"))
        screenshot("main-player-more.png")
        ui.onNodeWithText("查看专辑").performClick()
        ui.runOnIdle { assertEquals("album", album) }
    }

    @Test fun mainMenuPlaylistPickerOffersCreationAndKeepsTargetAfterSongChange() = models { music, player ->
        var current by mutableStateOf(state)
        ui.setContent { ClearTuneTheme { NowPlayingScreen(current, player, music, LyricsUiState(), false,
            {}, {}, {}, { _, _ -> }, {}) } }
        ui.onNodeWithContentDescription(context.getString(R.string.more_actions)).performClick()
        ui.onNodeWithText("添加到歌单").performClick()
        ui.runOnIdle { current = state.copy(currentSong = second, currentIndex = 1) }
        ui.onNodeWithText(context.getString(R.string.batch_new_playlist)).performClick()
        ui.onNodeWithText(context.getString(R.string.batch_playlist_name)).assertIsDisplayed()
        screenshot("player-new-playlist.png")
    }

    @Test fun missingLinksAndCompletedDownloadAreDisabled() = models { music, player ->
        ui.setContent { ClearTuneTheme { NowPlayingScreen(state.copy(currentSong = first.copy(albumId = null, artistId = null)),
            player, music, LyricsUiState(), false, {}, {}, {}, { _, _ -> }, {}, downloadState = DownloadState.COMPLETED) } }
        ui.onNodeWithContentDescription(context.getString(R.string.more_actions)).performClick()
        ui.onNodeWithText("查看专辑").assertIsNotEnabled()
        ui.onNodeWithText("查看歌手").assertIsNotEnabled()
        ui.onNodeWithText("已下载").assertIsNotEnabled()
    }

    @Test fun currentQueueMenuHidesNextAndOpensDetails() = queue {
        openQueueMenu(first.title)
        ui.onNodeWithText(context.getString(R.string.play_next)).assertDoesNotExist()
        order(listOf("添加到歌单", "喜欢", "歌曲详情", "从播放队列移除"))
        screenshot("queue-current-more.png")
        ui.onNodeWithText("歌曲详情").performClick()
        ui.onNode(isDialog()).assertExists()
        ui.onNode(hasText(first.title) and hasAnyAncestor(isDialog())).assertExists()
    }

    @Test fun otherQueueMenuOrderUsesCurrentFavoriteStateAndRetainsDrag() = queue {
        ui.onAllNodesWithContentDescription(context.getString(R.string.drag_to_reorder)).assertCountEquals(2)
        openQueueMenu(second.title)
        order(listOf("下一首播放", "添加到歌单", "取消喜欢", "歌曲详情", "从播放队列移除"))
        screenshot("queue-other-more.png")
    }

    private fun queue(check: () -> Unit) = models { music, player ->
        ui.setContent { ClearTuneTheme { QueueScreen(state, player, music, {}, {}, {}, {}) } }
        check()
    }

    private fun openQueueMenu(title: String) {
        ui.onNode(
            hasContentDescription(context.getString(R.string.more_actions)) and
                hasAnyAncestor(hasClickAction() and hasAnyDescendant(hasText(title))),
            useUnmergedTree = true,
        ).performClick()
    }

    private fun order(labels: List<String>) {
        val positions = labels.map { text -> ui.onNodeWithText(text).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top }
        assertEquals(positions.sorted(), positions)
    }

    private fun screenshot(name: String) {
        ui.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        java.io.File(context.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun models(check: (MusicViewModel, PlayerViewModel) -> Unit) {
        val username = "menu-${UUID.randomUUID()}"
        val key = accountStorageKey("https://example.invalid/", username)
        val db = DatabaseFactory.create(context, key)
        val session = AccountSession(ServerCredentials("https://example.invalid/", username, "test"),
            ServerProfile("https://example.invalid/", username, "test", "1", "1.16.1", true), "test", db)
        session.revoke()
        runBlocking { db.mediaDao().upsertSongs(listOf(first.toEntity(), second.toEntity())) }
        val store = ViewModelStore()
        lateinit var music: MusicViewModel
        lateinit var player: PlayerViewModel
        ui.runOnIdle {
            music = MusicViewModel(MusicRepository(session, OpenSubsonicApiFactory()), AppPreferences(context), session, context)
            player = PlayerViewModel(context, PlaybackRepository(context, session, OpenSubsonicApiFactory(), AppPreferences(context)), PlaybackPreferences(context))
            store.put("music", music); store.put("player", player)
        }
        try { check(music, player) } finally {
            ui.runOnIdle { store.clear() }
            db.close()
            context.deleteDatabase("cleartune_account_$key.db")
        }
    }
}
