package com.cleartune.app.library

import com.cleartune.core.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class MusicTagRefreshPolicyTest {
    private val original = Song("song", "Original", artistId = "artist-a", artistName = "Artist A",
        albumId = "album-a", albumName = "Album A", year = 2001, coverArtId = "cover-a")

    @Test fun titleOnlyDoesNotFetchAlbumOrArtistLists() {
        assertEquals(emptySet<String>() to emptySet<String>(),
            tagRelatedIds(original, original.copy(title = "New title")))
    }

    @Test fun albumRenameRefreshesOnlyItsAlbum() {
        assertEquals(setOf("album-a") to emptySet<String>(),
            tagRelatedIds(original, original.copy(albumName = "New album")))
    }

    @Test fun movingSongRefreshesBothOldAndNewRelations() {
        assertEquals(setOf("album-a", "album-b") to setOf("artist-a", "artist-b"),
            tagRelatedIds(original, original.copy(albumId = "album-b", artistId = "artist-b")))
    }
}
