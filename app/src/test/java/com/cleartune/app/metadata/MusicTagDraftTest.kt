package com.cleartune.app.metadata

import com.cleartune.core.model.*
import org.junit.Assert.*
import org.junit.Test

class MusicTagDraftTest {
    private val original = mapOf(MusicTagField.TITLE to "Original", MusicTagField.ARTIST to "Artist",
        MusicTagField.LYRICS to "Original lyrics", MusicTagField.COVER to "data:image/png;base64,original")
    private val snapshot = MusicTagSnapshot("/music/song.mp3", original)

    @Test fun openingAndRevertingEditsHaveNoChanges() {
        assertTrue(musicTagDraftChanges(snapshot, original).isEmpty())
        assertEquals(mapOf(MusicTagField.TITLE to "Edited"), musicTagDraftChanges(snapshot, original + (MusicTagField.TITLE to "Edited")))
        assertTrue(musicTagDraftChanges(snapshot, original + (MusicTagField.TITLE to "Original")).isEmpty())
        assertTrue(musicTagDraftChanges(null, original).isEmpty())
    }

    @Test fun candidateOnlyReplacesSelectedFieldsAndPreservesManualDraft() {
        val draft = original + (MusicTagField.LYRICS to "Manual lyrics")
        val candidate = mapOf(MusicTagField.TITLE to "Candidate", MusicTagField.ARTIST to "Other artist", MusicTagField.LYRICS to "", MusicTagField.COVER to "https://example.com/cover.jpg")
        val filled = fillMusicTagDraft(draft, candidate, setOf(MusicTagField.TITLE, MusicTagField.LYRICS))
        assertEquals("Candidate", filled[MusicTagField.TITLE])
        assertEquals("Manual lyrics", filled[MusicTagField.LYRICS])
        assertEquals("Artist", filled[MusicTagField.ARTIST])
        assertEquals(original[MusicTagField.COVER], filled[MusicTagField.COVER])
        assertEquals(setOf(MusicTagField.TITLE, MusicTagField.LYRICS), musicTagDraftChanges(snapshot, filled).keys)
    }
}
