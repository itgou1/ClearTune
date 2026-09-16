package com.cleartune.app

import org.junit.Assert.*
import org.junit.Test

class SongBatchSelectionTest {
    @Test fun longPressSelectsFirstSongAndZeroSelectionStaysInMode() {
        val state = SongBatchSelection()
        state.start("a")
        assertTrue(state.active)
        assertEquals(setOf("a"), state.ids)
        state.toggle("a")
        assertTrue(state.active)
        assertTrue(state.ids.isEmpty())
        state.clear()
        assertFalse(state.active)
    }

    @Test fun selectAllOnlyAffectsVisibleSongs() {
        val state = SongBatchSelection()
        state.start("hidden")
        state.toggleAll(setOf("a", "b"))
        assertEquals(setOf("hidden", "a", "b"), state.ids)
        state.toggleAll(setOf("a", "b"))
        assertEquals(setOf("hidden"), state.ids)
    }

    @Test fun removedSongsCannotBeSubmittedAndCancelClearsSelection() {
        val state = SongBatchSelection()
        state.start("a")
        state.toggle("b")
        state.retain(setOf("b", "c"))
        assertEquals(setOf("b"), state.ids)
        state.clear()
        assertTrue(state.ids.isEmpty())
    }

    @Test fun submittingLocksSelectionButFailureCanUnlockAndRetry() {
        val state = SongBatchSelection()
        state.start("a")
        state.busy = true
        state.toggle("b")
        state.toggleAll(setOf("b"))
        state.start("c")
        state.clear()
        assertTrue(state.active)
        assertEquals(setOf("a"), state.ids)
        state.busy = false
        state.toggle("b")
        assertEquals(setOf("a", "b"), state.ids)
    }
}
