package com.cleartune.feature.library

import com.cleartune.core.model.LibraryHome
import com.cleartune.core.model.TrackId
import com.cleartune.core.model.TrackSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryUiStateFactoryTest {
    @Test
    fun empty_library_that_has_not_requested_permission_offers_both_sources() {
        val state = LibraryUiStateFactory.create(
            home = LibraryHome(),
            localAccess = LocalAccessUiState.NOT_REQUESTED,
        )

        assertEquals(LibraryEmptyReason.READY_TO_SCAN, state.emptyReason)
        assertTrue(state.showScanAction)
        assertTrue(state.showAddWebDavAction)
    }

    @Test
    fun denied_local_access_never_blocks_webdav() {
        val state = LibraryUiStateFactory.create(
            home = LibraryHome(),
            localAccess = LocalAccessUiState.DENIED,
        )

        assertEquals(LibraryEmptyReason.PERMISSION_REQUIRED, state.emptyReason)
        assertTrue(state.showOpenSettingsAction)
        assertTrue(state.showAddWebDavAction)
    }

    @Test
    fun populated_library_keeps_content_visible_when_refresh_fails() {
        val home = LibraryHome(
            songCount = 7,
            albumCount = 3,
            artistCount = 2,
            recentAdded = (1..6).map(::track),
        )

        val state = LibraryUiStateFactory.create(
            home = home,
            localAccess = LocalAccessUiState.GRANTED,
            sync = LibrarySyncUiState.Failed("无法读取部分音乐"),
        )

        assertEquals(null, state.emptyReason)
        assertEquals("无法读取部分音乐", state.inlineMessage)
        assertEquals(4, state.recentAdded.size)
        assertFalse(state.showScanAction)
    }

    @Test
    fun category_ids_are_stable_and_counts_are_mapped() {
        val state = LibraryUiStateFactory.create(
            home = LibraryHome(songCount = 11, albumCount = 4, artistCount = 5),
            localAccess = LocalAccessUiState.GRANTED,
        )

        assertEquals(
            listOf("songs", "albums", "artists", "playlists", "folders", "downloads"),
            state.categories.map(LibraryCategoryUi::id),
        )
        assertEquals(listOf(11, 4, 5, null, null, null), state.categories.map(LibraryCategoryUi::count))
    }

    private fun track(index: Int) = TrackSummary(
        id = TrackId("track-$index"),
        title = "Track $index",
    )
}

