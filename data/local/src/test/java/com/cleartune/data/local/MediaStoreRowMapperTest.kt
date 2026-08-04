package com.cleartune.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaStoreRowMapperTest {
    private val mapper = MediaStoreRowMapper()

    @Test
    fun supported_audio_is_normalized_without_losing_the_content_uri() {
        val snapshot = mapper.map(
            MediaStoreRow(
                id = 42,
                displayName = "夜航.flac",
                relativePath = "Music\\Rock//Live/",
                dataPath = null,
                title = " ",
                album = "City Lights",
                artist = "Aster; Boreal",
                durationMs = 0,
                sizeBytes = 12_345,
                modifiedEpochSeconds = 99,
                mimeType = "audio/flac",
            ),
        )

        requireNotNull(snapshot)
        assertEquals("mediastore:42", snapshot.sourceKey)
        assertEquals("content://media/external/audio/media/42", snapshot.contentUri)
        assertEquals("Music/Rock/Live", snapshot.relativeFolder)
        assertEquals("夜航", snapshot.title)
        assertEquals(listOf("Aster", "Boreal"), snapshot.artistNames)
        assertNull(snapshot.durationMs)
    }

    @Test
    fun data_path_is_only_used_as_a_folder_fallback() {
        val snapshot = mapper.map(
            MediaStoreRow(
                id = 7,
                displayName = "track.mp3",
                relativePath = null,
                dataPath = "/storage/emulated/0/Music/Jazz/track.mp3",
                title = "Track",
                album = null,
                artist = null,
                durationMs = 1_000,
                sizeBytes = 5,
                modifiedEpochSeconds = 1,
                mimeType = "audio/mpeg",
            ),
        )

        assertEquals("Music/Jazz", requireNotNull(snapshot).relativeFolder)
    }

    @Test
    fun unsupported_extension_and_mime_are_rejected() {
        assertNull(
            mapper.map(
                MediaStoreRow(
                    id = 9,
                    displayName = "notes.pdf",
                    relativePath = "Downloads",
                    dataPath = null,
                    title = "Notes",
                    album = null,
                    artist = null,
                    durationMs = null,
                    sizeBytes = 10,
                    modifiedEpochSeconds = 1,
                    mimeType = "application/pdf",
                ),
            ),
        )
    }
}
