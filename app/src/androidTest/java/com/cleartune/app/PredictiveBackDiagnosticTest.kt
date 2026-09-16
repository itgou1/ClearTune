package com.cleartune.app

import android.os.Bundle
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import androidx.test.platform.app.InstrumentationRegistry
import com.cleartune.app.auth.AccountSession
import com.cleartune.app.library.*
import com.cleartune.core.database.DatabaseFactory
import com.cleartune.core.database.PlaylistSongEntity
import com.cleartune.core.database.toEntity
import com.cleartune.core.datastore.AppPreferences
import com.cleartune.core.model.*
import com.cleartune.core.network.OpenSubsonicApiFactory
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Uses real detail screens, production entry scoping, Room and unchanged transitions.
 * Dispatcher events intentionally bypass the OEM's physical gesture thresholds.
 */
class PredictiveBackDiagnosticTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private lateinit var nav: NavHostController

    @Test fun rightCancelRepeatedKeepsAlbumAndActions() = simulate()
    @Test fun leftCancelRepeatedKeepsAlbum() = simulate(right = false)
    @Test fun rightCommitReturnsArtistAndReleasesAlbum() = simulate(commit = true)
    @Test fun playlistCancelKeepsPlaylist() = simulate(destination = DetailKind.PLAYLIST)
    @Test fun twoAlbumEntriesDoNotShareState() = simulate(originKind = DetailKind.ALBUM)
    @Test fun invalidBackgroundCannotPopForegroundOnCancel() = simulate(invalidatePrevious = true)
    @Test fun invalidPreviousExitsOnlyAfterCommittedReturn() = simulate(invalidatePrevious = true, commit = true)

    private fun simulate(
        right: Boolean = true,
        commit: Boolean = false,
        destination: DetailKind = DetailKind.ALBUM,
        originKind: DetailKind = DetailKind.ARTIST,
        invalidatePrevious: Boolean = false,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val url = "https://${UUID.randomUUID()}.invalid"
        val account = accountStorageKey(url, "back-regression")
        val database = DatabaseFactory.create(context, account)
        val session = AccountSession(ServerCredentials(url, "back-regression", "test"),
            ServerProfile(url, "back-regression", "test", "1", "1.16.1", true), "test", database)
        session.revoke()
        val songA = Song("song-a", "Song A", artistId = "artist-a", albumId = "album-a")
        val songB = Song("song-b", "Song B", artistId = "artist-a", albumId = "album-b")
        runBlocking {
            val dao = database.mediaDao()
            dao.upsertArtists(listOf(Artist("artist-a", "Artist A").toEntity()))
            dao.upsertAlbums(listOf(Album("album-a", "Album A", artistId = "artist-a").toEntity(),
                Album("album-b", "Album B", artistId = "artist-a").toEntity()))
            dao.upsertSongs(listOf(songA.toEntity(), songB.toEntity()))
            dao.upsertPlaylistDetail(Playlist("playlist-a", "Playlist A", songCount = 1).toEntity())
            dao.upsertPlaylistSongs(listOf(PlaylistSongEntity("playlist-a", "song-a", 0)))
        }
        lateinit var music: MusicViewModel
        val models = mutableMapOf<String, DetailViewModel>()
        val mounts = AtomicInteger()
        val foregroundSelection = SongBatchSelection()
        var played: List<String> = emptyList()
        var downloaded: List<String> = emptyList()
        ui.runOnIdle { music = MusicViewModel(MusicRepository(session, OpenSubsonicApiFactory()), AppPreferences(context), session) }
        val origin = DetailTarget(originKind, if (originKind == DetailKind.ARTIST) "artist-a" else "album-b")
        val target = DetailTarget(destination, if (destination == DetailKind.PLAYLIST) "playlist-a" else "album-a")
        try {
            ui.setContent {
                nav = rememberNavController()
                val top by nav.currentBackStackEntryAsState()
                NavHost(nav, startDestination = "home", modifier = Modifier.fillMaxSize(),
                    enterTransition = { fadeIn(ClearTuneMotion.standard()) + slideInHorizontally(
                        initialOffsetX = { it / 10 }, animationSpec = ClearTuneMotion.emphasized()) },
                    exitTransition = { fadeOut(ClearTuneMotion.quick()) + slideOutHorizontally(
                        targetOffsetX = { -it / 16 }, animationSpec = ClearTuneMotion.standard()) },
                    popEnterTransition = { fadeIn(ClearTuneMotion.standard()) + slideInHorizontally(
                        initialOffsetX = { -it / 10 }, animationSpec = ClearTuneMotion.emphasized()) },
                    popExitTransition = { fadeOut(ClearTuneMotion.quick()) + slideOutHorizontally(
                        targetOffsetX = { it / 16 }, animationSpec = ClearTuneMotion.standard()) },
                ) {
                    composable("home") { Text("Home") }
                    DetailKind.entries.forEach { kind ->
                        composable("${kind.route}/{id}") { entry ->
                            val item = DetailTarget(kind, entry.arguments!!.getString("id")!!)
                            val detail = entryDetailViewModel(entry, music, item)
                            models[entry.id] = detail
                            val state by detail.state.collectAsStateWithLifecycle()
                            DetailInvalidationEffect(entry, nav, detail) { }
                            val selection = detailSelection(entry.id, top?.id, foregroundSelection)
                            DisposableEffect(entry) {
                                if (item == origin) mounts.incrementAndGet()
                                onDispose { }
                            }
                            val play: (List<Song>, Int) -> Unit = { songs, _ -> played = songs.map(Song::id) }
                            val download: (List<Song>) -> Unit = { songs -> downloaded = songs.map(Song::id) }
                            Box(Modifier.fillMaxSize().testTag(item.route)) {
                                when (kind) {
                                    DetailKind.ALBUM -> AlbumDetailScreen(selection, state, emptyList(), music,
                                        { nav.popBackStack() }, play, download)
                                    DetailKind.ARTIST -> ArtistDetailScreen(selection, state, emptyList(), emptyList(), music,
                                        { }, { nav.popBackStack() }, play, download)
                                    DetailKind.PLAYLIST -> PlaylistDetailScreen(state, music, { nav.popBackStack() },
                                        play, download, listOf(songA, songB), { _, _ -> }, { _, _ -> }, { _, _ -> })
                                }
                            }
                        }
                    }
                }
            }
            ui.runOnIdle { nav.navigate(origin.route) }
            waitResumed()
            val previousId = nav.currentBackStackEntry!!.id
            ui.waitUntil(10_000) { models[previousId]?.state?.value?.songs?.isNotEmpty() == true }
            ui.runOnIdle { nav.navigate(target.route) }
            waitResumed()
            val currentId = nav.currentBackStackEntry!!.id
            val previous = models.getValue(previousId)
            val current = models.getValue(currentId)
            ui.waitUntil(10_000) { current.state.value.songs.map(Song::id) == listOf("song-a") }
            assertNotSame(previous, current)
            if (invalidatePrevious) ui.runOnIdle { previous.markDeleted() }
            ui.onNodeWithText("Song A").performScrollTo()
            val initialSongY = ui.onNodeWithText("Song A").fetchSemanticsNode().positionInRoot.y
            val initialX = ui.onNodeWithTag(target.route).fetchSemanticsNode().positionInRoot.x
            val edge = if (right) BackEventCompat.EDGE_RIGHT else BackEventCompat.EDGE_LEFT
            val width = context.resources.displayMetrics.widthPixels.toFloat()
            fun event(progress: Float) = BackEventCompat(
                (if (right) width - 1 else 1f) + (if (right) -1 else 1) * width * progress * .5f,
                500f, progress, edge)
            for (progress in if (commit) listOf(.35f) else listOf(.15f, .35f, .8f)) {
                val oldMounts = mounts.get()
                ui.runOnIdle { ui.activity.onBackPressedDispatcher.dispatchOnBackStarted(event(0f)) }
                ui.waitForIdle()
                ui.runOnIdle { ui.activity.onBackPressedDispatcher.dispatchOnBackProgressed(event(progress)) }
                ui.waitUntil(10_000) { mounts.get() > oldMounts }
                ui.waitForIdle()
                assertEquals(currentId, nav.currentBackStackEntry!!.id)
                assertEquals(listOf("song-a"), current.state.value.songs.map(Song::id))
                assertSame(previous, models[previousId])
                assertSame(current, models[currentId])
                assertTrue("Existing horizontal animation is preserved",
                    ui.onNodeWithTag(target.route).fetchSemanticsNode().positionInRoot.x > initialX)
                ui.runOnIdle {
                    if (commit) ui.activity.onBackPressedDispatcher.onBackPressed()
                    else ui.activity.onBackPressedDispatcher.dispatchOnBackCancelled()
                }
                waitResumed()
                if (!commit) {
                    assertEquals(currentId, nav.currentBackStackEntry!!.id)
                    assertEquals(listOf("song-a"), current.state.value.songs.map(Song::id))
                    assertTrue(current.active)
                    assertEquals(initialSongY, ui.onNodeWithText("Song A").fetchSemanticsNode().positionInRoot.y, 1f)
                }
            }
            if (commit) {
                ui.waitUntil(10_000) { !current.active }
                assertEquals(if (invalidatePrevious) "home" else "${originKind.route}/{id}", nav.currentDestination?.route)
            } else {
                runBlocking { database.mediaDao().upsertSongs(listOf(songA.copy(starredAt = 123L).toEntity())) }
                ui.waitUntil(10_000) { current.state.value.songs.single().starredAt == 123L }
                ui.onNodeWithText("Song A").performScrollTo().performClick()
                assertEquals(listOf("song-a"), played)
                val downloadLabel = context.getString(R.string.download_action)
                val downloadButton = if (destination == DetailKind.PLAYLIST)
                    ui.onNodeWithContentDescription(downloadLabel) else ui.onNodeWithText(downloadLabel)
                downloadButton.performScrollTo().performClick()
                assertEquals(listOf("song-a"), downloaded)
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                putString("stream", "BACK_REGRESSION origin=${origin.route} target=${target.route} right=$right " +
                    "commit=$commit invalidatePrevious=$invalidatePrevious passed\n")
            })
        } finally {
            ui.runOnIdle { music.viewModelScope.cancel() }
            database.close()
            context.deleteDatabase("cleartune_account_$account.db")
        }
    }

    private fun waitResumed() {
        ui.waitForIdle()
        ui.waitUntil(10_000) { nav.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED }
        ui.waitForIdle()
    }
}
