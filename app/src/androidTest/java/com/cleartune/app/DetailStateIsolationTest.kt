package com.cleartune.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import com.cleartune.app.library.*
import com.cleartune.core.model.Album
import com.cleartune.core.model.ClearTuneError
import com.cleartune.core.model.Song
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DetailStateIsolationTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()

    private class Source : DetailSource {
        val snapshots = mutableMapOf<DetailTarget, MutableStateFlow<DetailUiState>>()
        val responses = mutableMapOf<DetailTarget, CompletableDeferred<ClearTuneError?>>()
        val requests = mutableMapOf<DetailTarget, Int>()
        var ignoreCancellation = false
        fun seed(target: DetailTarget, title: String = target.id) {
            snapshots[target] = MutableStateFlow(DetailUiState(album = Album(target.id, title),
                songs = listOf(Song("song-${target.id}", title, albumId = target.id))))
            responses[target] = CompletableDeferred()
        }
        override fun observe(target: DetailTarget): Flow<DetailUiState> = snapshots.getValue(target)
        override suspend fun refresh(target: DetailTarget): ClearTuneError? {
            requests[target] = (requests[target] ?: 0) + 1
            return if (ignoreCancellation) withContext(NonCancellable) { responses.getValue(target).await() }
                else responses.getValue(target).await()
        }
    }

    @Test fun cachedContentSurvivesSlowRefreshFailureAndDatabaseUpdates() {
        val target = DetailTarget(DetailKind.ALBUM, "a")
        val source = Source().apply { seed(target) }
        val account = Job()
        lateinit var detail: DetailViewModel
        try {
            ui.runOnIdle { detail = DetailViewModel(target, source, account, emptyFlow()) }
            assertFalse(detail.state.value.isLoading)
            assertEquals("a", detail.state.value.album?.id)
            ui.runOnIdle { source.responses.getValue(target).complete(ClearTuneError.Timeout()) }
            ui.waitUntil { detail.state.value.errorMessage != null }
            assertEquals("a", detail.state.value.album?.id)
            ui.runOnIdle {
                source.snapshots.getValue(target).update { it.copy(songs = it.songs.map { song -> song.copy(starredAt = 42) }) }
            }
            ui.waitUntil { detail.state.value.songs.single().starredAt == 42L }
            assertEquals(1, source.requests[target])
        } finally { ui.runOnIdle { account.cancel() } }
    }

    @Test fun twoAlbumsRefreshIndependentlyAndReleaseIndependently() {
        val a = DetailTarget(DetailKind.ALBUM, "a")
        val b = DetailTarget(DetailKind.ALBUM, "b")
        val source = Source().apply { seed(a); seed(b) }
        val account = Job()
        val storeA = ViewModelStore()
        val storeB = ViewModelStore()
        lateinit var first: DetailViewModel
        lateinit var second: DetailViewModel
        try {
            ui.runOnIdle {
                first = DetailViewModel(a, source, account, emptyFlow()).also { storeA.put("detail", it) }
                second = DetailViewModel(b, source, account, emptyFlow()).also { storeB.put("detail", it) }
                source.responses.getValue(b).complete(null)
            }
            assertEquals("a", first.state.value.album?.id)
            assertEquals("b", second.state.value.album?.id)
            ui.runOnIdle { storeA.clear() }
            assertFalse(first.active)
            assertTrue(second.active)
            assertTrue(account.isActive)
            assertEquals("b", second.state.value.album?.id)
        } finally { ui.runOnIdle { storeA.clear(); storeB.clear(); account.cancel() } }
    }

    @Test fun accountCancellationRejectsLateResponseAndKeepsNewAccountSeparate() {
        val target = DetailTarget(DetailKind.ALBUM, "same-id")
        val oldSource = Source().apply { seed(target, "Old account"); ignoreCancellation = true }
        val newSource = Source().apply { seed(target, "New account") }
        val oldAccount = Job()
        val newAccount = Job()
        lateinit var old: DetailViewModel
        lateinit var fresh: DetailViewModel
        try {
            ui.runOnIdle {
                old = DetailViewModel(target, oldSource, oldAccount, emptyFlow())
                oldAccount.cancel()
                fresh = DetailViewModel(target, newSource, newAccount, emptyFlow())
                oldSource.responses.getValue(target).complete(ClearTuneError.Server(70))
            }
            ui.waitUntil { old.state.value.album == null }
            assertFalse(old.active)
            assertNull(old.invalidation.value)
            assertEquals("New account", fresh.state.value.album?.name)
            assertTrue(fresh.active)
        } finally {
            oldSource.responses.getValue(target).complete(null)
            ui.runOnIdle { oldAccount.cancel(); newAccount.cancel() }
        }
    }

    @Test fun missingAndActionInvalidationsAreStickyAndTargeted() {
        val a = DetailTarget(DetailKind.ALBUM, "a")
        val b = DetailTarget(DetailKind.PLAYLIST, "b")
        val source = Source().apply { seed(a); seed(b) }
        val account = Job()
        val events = MutableSharedFlow<String>(extraBufferCapacity = 1)
        lateinit var first: DetailViewModel
        lateinit var second: DetailViewModel
        try {
            ui.runOnIdle {
                first = DetailViewModel(a, source, account, events)
                second = DetailViewModel(b, source, account, events)
                source.responses.getValue(a).complete(ClearTuneError.Server(70))
            }
            ui.waitUntil { first.invalidation.value == DetailInvalidation.MISSING }
            assertNull(second.invalidation.value)
            ui.runOnIdle { events.tryEmit(b.route) }
            ui.waitUntil { second.invalidation.value == DetailInvalidation.MISSING }
            assertEquals(DetailInvalidation.MISSING, first.invalidation.value)
        } finally { ui.runOnIdle { account.cancel() } }
    }

    @Test fun previewCannotFilterForegroundSelection() {
        val selected = SongBatchSelection().apply { start("album-song") }
        ui.setContent {
            val preview = detailSelection("artist-entry", "album-entry", selected)
            LaunchedEffect(Unit) { preview.retain(setOf("artist-song")) }
        }
        ui.waitForIdle()
        assertEquals(setOf("album-song"), selected.ids)
        assertTrue(selected.active)
    }
}
