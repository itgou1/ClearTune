package com.cleartune.app

import com.cleartune.core.contracts.PlaybackLibraryRepository
import com.cleartune.core.model.LocationId
import com.cleartune.core.model.LocationType
import com.cleartune.core.model.PlayableTrack
import com.cleartune.core.model.SourceId
import com.cleartune.core.model.Track
import com.cleartune.core.model.TrackId
import com.cleartune.core.model.TrackLocation
import com.cleartune.feature.player.LrcLine
import com.cleartune.feature.player.LyricsUiState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackLyricsResolverTest {
    @Test
    fun `resolver uses same-name local sidecar before remote and emits parsed timeline`() = runBlocking {
        val trackId = TrackId("track")
        val local = location(trackId, "local", LocationType.LOCAL_URI, "content://media/audio/7", "Music/Song.flac")
        val remote = location(trackId, "remote", LocationType.REMOTE_URL, "https://dav.test/Music/Song.flac", "Music/Song.flac")
        val reads = mutableListOf<LocationType>()
        val resolver = TrackLyricsResolver(
            library = FixedPlaybackLibrary(PlayableTrack(Track(trackId, "Song"), listOf(remote, local))),
            sidecars = LyricsSidecarReader { location ->
                reads += location.type
                if (location.type == LocationType.LOCAL_URI) "[00:01]Local line".toByteArray() else null
            },
        )

        assertEquals(LyricsUiState.Available(listOf(LrcLine(1_000, "Local line"))), resolver.resolve(trackId))
        assertEquals(listOf(LocationType.LOCAL_URI), reads)
    }

    @Test
    fun `reader failures become typed unavailable rather than escaping`() = runBlocking {
        val trackId = TrackId("track")
        val resolver = TrackLyricsResolver(
            library = FixedPlaybackLibrary(PlayableTrack(Track(trackId, "Song"), listOf(location(trackId, "remote", LocationType.REMOTE_URL, "https://dav.test/Song.flac", "Song.flac")))),
            sidecars = LyricsSidecarReader { error("network secret") },
        )

        assertEquals(LyricsUiState.Unavailable, resolver.resolve(trackId))
    }

    private fun location(trackId: TrackId, id: String, type: LocationType, uri: String, key: String) = TrackLocation(
        LocationId(id), trackId, SourceId("source"), key, type, uri,
    )
}

private class FixedPlaybackLibrary(private val track: PlayableTrack?) : PlaybackLibraryRepository {
    override suspend fun getPlayableTrack(trackId: TrackId): PlayableTrack? = track
}
