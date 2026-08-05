package com.cleartune.playback

import com.cleartune.core.model.LocationId
import com.cleartune.core.model.LocationType
import com.cleartune.core.model.PlayableTrack
import com.cleartune.core.model.SourceId
import com.cleartune.core.model.Track
import com.cleartune.core.model.TrackId
import com.cleartune.core.model.TrackLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationResolverTest {
    private val trackId = TrackId("track-1")

    @Test
    fun completed_download_precedes_local_and_remote_locations() {
        val resolution = LocationResolver.resolve(
            playableTrack = playable(
                location("remote", LocationType.REMOTE_URL, "https://music.example/song.mp3"),
                location("local", LocationType.LOCAL_URI, "content://music/song"),
                location("download", LocationType.DOWNLOADED_FILE, "/offline/song.mp3"),
            ),
            fileExists = { true },
            uriReadable = { true },
            networkAvailable = true,
        )

        val ready = resolution as LocationResolution.Ready
        assertEquals(listOf("download", "local", "remote"), ready.attempts.map { it.id.value })
    }

    @Test
    fun stale_download_and_revoked_uri_fall_back_to_remote() {
        val resolution = LocationResolver.resolve(
            playableTrack = playable(
                location("download", LocationType.DOWNLOADED_FILE, "/offline/missing.mp3"),
                location("local", LocationType.LOCAL_URI, "content://music/revoked"),
                location("remote", LocationType.REMOTE_URL, "https://music.example/song.mp3"),
            ),
            fileExists = { false },
            uriReadable = { false },
            networkAvailable = true,
        )

        val ready = resolution as LocationResolution.Ready
        assertEquals(listOf("remote"), ready.attempts.map { it.id.value })
    }

    @Test
    fun offline_remote_only_track_has_user_safe_failure() {
        val resolution = LocationResolver.resolve(
            playableTrack = playable(location("remote", LocationType.REMOTE_URL, "https://music.example/song.mp3")),
            fileExists = { true },
            uriReadable = { true },
            networkAvailable = false,
        )

        assertEquals(LocationResolution.Unavailable(PlaybackFailure.NetworkUnavailable), resolution)
    }

    @Test
    fun unavailable_records_are_never_attempted() {
        val unavailable = location("disabled", LocationType.LOCAL_URI, "content://music/song", available = false)
        val resolution = LocationResolver.resolve(
            playableTrack = playable(unavailable),
            fileExists = { true },
            uriReadable = { true },
            networkAvailable = true,
        )

        assertTrue(resolution is LocationResolution.Unavailable)
    }

    private fun playable(vararg locations: TrackLocation) = PlayableTrack(
        track = Track(id = trackId, title = "Song"),
        locations = locations.toList(),
    )

    private fun location(
        id: String,
        type: LocationType,
        uri: String,
        available: Boolean = true,
    ) = TrackLocation(
        id = LocationId(id),
        trackId = trackId,
        sourceId = SourceId("source"),
        sourceKey = id,
        type = type,
        uri = uri,
        available = available,
    )
}
