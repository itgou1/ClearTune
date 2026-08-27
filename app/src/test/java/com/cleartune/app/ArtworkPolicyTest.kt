package com.cleartune.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkPolicyTest {
    @Test
    fun legacyNavidromeAlbumPlaceholderIsTreatedAsMissing() {
        assertNull("al-750mHi2tYZisNLM19Ym2Ol_0".displayableArtworkId())
        assertNull("AL-750mHi2tYZisNLM19Ym2Ol_000".displayableArtworkId())
        assertNull(" al-750mHi2tYZisNLM19Ym2Ol_0 ".displayableArtworkId())
    }

    @Test
    fun realAndNonNavidromeArtworkIdsArePreserved() {
        assertEquals(
            "mf-song_6a8fadce",
            "mf-song_6a8fadce".displayableArtworkId(),
        )
        assertEquals("custom-cover_0", "custom-cover_0".displayableArtworkId())
    }

    @Test
    fun missingArtworkRemainsMissing() {
        assertNull(null.displayableArtworkId())
        assertNull("   ".displayableArtworkId())
    }
}
