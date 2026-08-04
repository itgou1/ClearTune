package com.cleartune.core.designsystem.component

import com.cleartune.core.model.ArtistId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistAvatarSelectorTest {
    @Test
    fun same_artist_always_maps_to_same_avatar() {
        val artistId = ArtistId("artist-a")
        assertEquals(artistAvatarIndex(artistId), artistAvatarIndex(artistId))
    }

    @Test
    fun avatar_index_is_always_inside_resource_range() {
        repeat(100) { index ->
            assertTrue(artistAvatarIndex(ArtistId("artist-$index")) in 0..7)
        }
    }
}
