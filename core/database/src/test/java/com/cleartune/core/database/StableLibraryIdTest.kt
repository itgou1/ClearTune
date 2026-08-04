package com.cleartune.core.database

import com.cleartune.core.database.model.StableLibraryId
import com.cleartune.core.model.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class StableLibraryIdTest {
    @Test
    fun the_same_source_key_keeps_the_same_track_and_location_ids() {
        val sourceId = SourceId("local")

        assertEquals(
            StableLibraryId.track(sourceId, "mediastore:42"),
            StableLibraryId.track(sourceId, "mediastore:42"),
        )
        assertEquals(
            StableLibraryId.location(sourceId, "mediastore:42"),
            StableLibraryId.location(sourceId, "mediastore:42"),
        )
    }

    @Test
    fun different_source_keys_do_not_collapse_to_the_same_ids() {
        val sourceId = SourceId("local")

        assertNotEquals(
            StableLibraryId.track(sourceId, "mediastore:42"),
            StableLibraryId.track(sourceId, "mediastore:43"),
        )
    }
}
