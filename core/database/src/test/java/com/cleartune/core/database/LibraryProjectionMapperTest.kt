package com.cleartune.core.database

import com.cleartune.core.database.model.LibraryTrackRow
import com.cleartune.core.database.model.belongsToFolder
import com.cleartune.core.database.model.toTrackSummary
import com.cleartune.core.model.TrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryProjectionMapperTest {
    @Test
    fun track_projection_preserves_ordered_artists_and_download_state() {
        val summary = LibraryTrackRow(
            trackId = "track-1",
            title = "Night Drive",
            albumTitle = "City Lights",
            artistNames = "Aster\u001fBoreal",
            artworkRef = "content://art/1",
            durationMs = 245_000,
            downloadedLocations = 1,
        ).toTrackSummary()

        assertEquals(TrackId("track-1"), summary.id)
        assertEquals(listOf("Aster", "Boreal"), summary.artistNames)
        assertEquals("City Lights", summary.albumTitle)
        assertTrue(summary.downloaded)
    }

    @Test
    fun blank_artist_projection_becomes_an_empty_list() {
        val summary = LibraryTrackRow(
            trackId = "track-2",
            title = "Unknown Artist",
            albumTitle = null,
            artistNames = null,
            artworkRef = null,
            durationMs = null,
            downloadedLocations = 0,
        ).toTrackSummary()

        assertEquals(emptyList<String>(), summary.artistNames)
        assertFalse(summary.downloaded)
    }

    @Test
    fun folder_membership_uses_an_exact_normalized_path() {
        val row = LibraryTrackRow(
            trackId = "track-3",
            title = "Folder Track",
            albumTitle = null,
            artistNames = null,
            artworkRef = null,
            durationMs = null,
            downloadedLocations = 0,
            relativeFolders = "Music/Rock\u001fMusic/Rock/Live",
        )

        assertTrue(row.belongsToFolder("Music/Rock"))
        assertFalse(row.belongsToFolder("Music"))
    }
}
