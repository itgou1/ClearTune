package com.cleartune.app

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.cleartune.app.auth.AccountSession
import com.cleartune.app.library.*
import com.cleartune.app.share.ShareTarget
import com.cleartune.core.database.DatabaseFactory
import com.cleartune.core.database.toEntity
import com.cleartune.core.datastore.AppPreferences
import com.cleartune.core.designsystem.ClearTuneTheme
import com.cleartune.core.model.*
import com.cleartune.core.network.OpenSubsonicApiFactory
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class FavoriteSongsScreenTest {
    @get:Rule val ui = createComposeRule()

    @Test fun playlistLayoutSupportsFavoriteAdditionRemovalSortingAndSnapshotShare() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val user = UUID.randomUUID().toString()
        val key = accountStorageKey("https://example.invalid/", user)
        val database = DatabaseFactory.create(context, key)
        val songs = listOf(Song("a", "Alpha", starredAt = 20, createdAt = 100),
            Song("b", "Beta", starredAt = 10, createdAt = 999), Song("c", "New fixture"))
        runBlocking { database.mediaDao().upsertSongs(songs.map { it.toEntity() }) }
        val session = AccountSession(ServerCredentials("https://example.invalid/", user, "test"),
            ServerProfile("https://example.invalid/", user, "test", "1", "1.16.1", true), "ui", database)
        session.revoke()
        lateinit var model: MusicViewModel
        var played = emptyList<String>()
        var shared: ShareTarget? = null
        var downloaded = emptyList<String>()
        ui.runOnIdle { model = MusicViewModel(MusicRepository(session, OpenSubsonicApiFactory()),
            AppPreferences(context), session, context) }
        try {
            ui.setContent {
                val library by model.libraryState.collectAsState()
                ClearTuneTheme { FavoriteSongsScreen(library.songs.filter { it.starredAt != null }, model,
                    onBack = {}, onPlay = { list, _ -> played = list.map { it.id } }, allSongs = library.songs,
                    onShare = { shared = it }, onDownload = { downloaded = it.map { song -> song.id } }) }
            }
            ui.waitUntil(10_000) { ui.onAllNodes(hasContentDescription("排序") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText(context.getString(R.string.play_all)).performClick()
            ui.runOnIdle { assertEquals(listOf("a", "b"), played) }
            ui.onNodeWithContentDescription("排序").performClick()
            ui.onNodeWithText("最近收藏").performClick()
            ui.onNodeWithText("最近收藏").assertDoesNotExist()
            ui.onNodeWithContentDescription("排序").performClick()
            ui.onNodeWithText("最近收藏").performClick()
            ui.waitForIdle()
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(context.getExternalFilesDir(null), "favorites-detail.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            ui.onNodeWithContentDescription("分享我喜欢的音乐").performClick()
            ui.runOnIdle { assertEquals(listOf("b", "a"), shared!!.snapshotSongIds) }
            ui.onNodeWithContentDescription(context.getString(R.string.more_short)).performClick()
            ui.onNodeWithText(context.getString(R.string.rename_playlist)).assertDoesNotExist()
            ui.onNodeWithText(context.getString(R.string.delete_playlist)).assertDoesNotExist()
            ui.onNodeWithText("下载全部").performClick()
            ui.runOnIdle { assertEquals(listOf("b", "a"), downloaded) }
            ui.onNodeWithContentDescription(context.getString(R.string.add_short)).performClick()
            ui.onNode(hasText("New fixture") and hasClickAction()).performClick()
            ui.onNodeWithText(context.getString(R.string.picker_add_count, 1)).performClick()
            ui.waitUntil(10_000) { model.libraryState.value.songs.first { it.id == "c" }.starredAt != null }
            ui.waitUntil(5_000) { ui.onAllNodesWithText(context.getString(R.string.song_count, 3)).fetchSemanticsNodes().isNotEmpty() }
            ui.onNode(hasText("New fixture") and hasClickAction()).performScrollTo().performTouchInput { longClick() }
            ui.onNodeWithText("取消喜欢").performClick()
            ui.onNode(hasText("取消喜欢") and hasAnyAncestor(isDialog())).performClick()
            ui.waitUntil(5_000) { model.libraryState.value.songs.first { it.id == "c" }.starredAt == null }
            ui.runOnIdle { assertNotNull(model.libraryState.value.songs.first { it.id == "a" }.starredAt) }
        } finally {
            ui.runOnIdle { model.viewModelScope.cancel() }
            database.close(); context.deleteDatabase("cleartune_account_$key.db")
        }
    }
}
