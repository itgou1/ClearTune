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
    fun legacy_removable_volume_mount_is_not_exposed_as_part_of_the_folder() {
        assertEquals("Music/Jazz", normalizeFolder("/storage/1234-5678/Music/Jazz/"))
        assertEquals("Music/Jazz", normalizeFolder("/mnt/media_rw/1234-5678/Music/Jazz/"))
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

    @Test
    fun damaged_music_rows_with_a_stable_id_retain_the_previous_location() {
        val missingName = row(id = 10, displayName = null, mimeType = null)
        val invalidSize = row(id = 11, displayName = "track.mp3", sizeBytes = -1, mimeType = "application/octet-stream")

        assertEquals(true, mapper.shouldRetainPreviousOnMappingFailure(missingName))
        assertEquals(true, mapper.shouldRetainPreviousOnMappingFailure(invalidSize))
    }

    @Test
    fun conclusively_unsupported_rows_do_not_retain_previous_locations() {
        assertEquals(
            false,
            mapper.shouldRetainPreviousOnMappingFailure(
                row(id = 12, displayName = "notes.pdf", mimeType = "application/pdf"),
            ),
        )
    }

    private fun row(
        id: Long,
        displayName: String?,
        sizeBytes: Long = 10,
        mimeType: String?,
    ) = MediaStoreRow(
        id = id,
        displayName = displayName,
        relativePath = "Music",
        dataPath = null,
        title = null,
        album = null,
        artist = null,
        durationMs = null,
        sizeBytes = sizeBytes,
        modifiedEpochSeconds = 1,
        mimeType = mimeType,
    )
}
