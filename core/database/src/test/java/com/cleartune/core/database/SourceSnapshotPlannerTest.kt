package com.cleartune.core.database

import com.cleartune.core.database.entity.TrackEntity
import com.cleartune.core.database.entity.TrackLocationEntity
import com.cleartune.core.database.model.LibraryIngestRecord
import com.cleartune.core.model.LocationType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSnapshotPlannerTest {
    @Test
    fun unchanged_rescan_does_not_write_track_or_location_and_keeps_original_added_time() {
        val existingTrack = track(addedAtEpochMs = 100)
        val existingLocation = location()
        val incoming = record(addedAtEpochMs = 999)

        val plan = SourceSnapshotPlanner.plan(
            existingTrack = existingTrack,
            existingLocation = existingLocation,
            existingArtistNames = listOf("Artist"),
            desiredAlbumId = "album-1",
            incoming = incoming,
        )

        assertFalse(plan.requiresWrite)
        assertTrue(plan.track.addedAtEpochMs == 100L)
    }

    @Test
    fun metadata_or_location_change_requires_an_update() {
        val titleChange = SourceSnapshotPlanner.plan(
            existingTrack = track(),
            existingLocation = location(),
            existingArtistNames = listOf("Artist"),
            desiredAlbumId = "album-1",
            incoming = record(title = "Renamed"),
        )
        val locationChange = SourceSnapshotPlanner.plan(
            existingTrack = track(),
            existingLocation = location(),
            existingArtistNames = listOf("Artist"),
            desiredAlbumId = "album-1",
            incoming = record(modifiedEpochSeconds = 22),
        )

        assertTrue(titleChange.requiresWrite)
        assertTrue(locationChange.requiresWrite)
    }

    private fun track(addedAtEpochMs: Long = 100) = TrackEntity(
        id = "track-1",
        title = "Track",
        durationMs = 1_000,
        albumId = "album-1",
        artworkRef = null,
        addedAtEpochMs = addedAtEpochMs,
    )

    private fun location() = TrackLocationEntity(
        id = "location-1",
        trackId = "track-1",
        sourceId = "local",
        sourceKey = "mediastore:1",
        type = LocationType.LOCAL_URI.name,
        uri = "content://media/external/audio/media/1",
        available = true,
        sizeBytes = 10,
        etag = null,
        relativeFolder = "Music",
        displayName = "Track.mp3",
        modifiedEpochSeconds = 1,
    )

    private fun record(
        title: String = "Track",
        modifiedEpochSeconds: Long = 1,
        addedAtEpochMs: Long = 100,
    ) = LibraryIngestRecord(
        sourceKey = "mediastore:1",
        uri = "content://media/external/audio/media/1",
        displayName = "Track.mp3",
        relativeFolder = "Music",
        title = title,
        albumTitle = "Album",
        artistNames = listOf("Artist"),
        durationMs = 1_000,
        artworkRef = null,
        sizeBytes = 10,
        modifiedEpochSeconds = modifiedEpochSeconds,
        addedAtEpochMs = addedAtEpochMs,
    )
}
