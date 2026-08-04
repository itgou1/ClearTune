package com.cleartune.playback

import com.cleartune.core.model.LocationId
import com.cleartune.core.model.LocationType
import com.cleartune.core.model.SourceId
import com.cleartune.core.model.Track
import com.cleartune.core.model.TrackId
import com.cleartune.core.model.TrackLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SecureMediaDescriptorTest {
    @Test
    fun descriptor_maps_metadata_and_supported_mime_type() {
        val descriptor = SecureMediaDescriptorFactory.create(
            track = Track(TrackId("track"), "Song", artworkRef = "content://art/1"),
            location = location("https://music.example/album/song.flac"),
        )

        assertEquals("track", descriptor.mediaId)
        assertEquals("Song", descriptor.title)
        assertEquals("audio/flac", descriptor.mimeType)
        assertEquals("content://art/1", descriptor.artworkUri)
    }

    @Test
    fun credentials_embedded_in_remote_url_are_rejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SecureMediaDescriptorFactory.create(
                track = Track(TrackId("track"), "Song"),
                location = location("https://person:secret@music.example/song.mp3"),
            )
        }
    }

    @Test
    fun descriptor_string_never_contains_playback_uri() {
        val descriptor = SecureMediaDescriptorFactory.create(
            track = Track(TrackId("track"), "Song"),
            location = location("https://private.example/song.mp3"),
        )

        assertFalse(descriptor.toString().contains("private.example"))
    }

    @Test
    fun credential_bearing_artwork_uri_is_removed_from_metadata() {
        val descriptor = SecureMediaDescriptorFactory.create(
            track = Track(TrackId("track"), "Song", artworkRef = "https://person:secret@art.example/cover.jpg"),
            location = location("https://music.example/song.mp3"),
        )

        assertNull(descriptor.artworkUri)
    }

    private fun location(uri: String) = TrackLocation(
        id = LocationId("location"),
        trackId = TrackId("track"),
        sourceId = SourceId("source"),
        sourceKey = "song",
        type = LocationType.REMOTE_URL,
        uri = uri,
    )
}
