package com.cleartune.app

import com.cleartune.core.model.Album
import com.cleartune.core.model.Artist
import com.cleartune.core.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkPolicyTest {
    @Test
    fun legacyNavidromeAlbumPlaceholderIsTreatedAsMissing() {
        assertNull("al-750mHi2tYZisNLM19Ym2Ol_0".displayableArtworkId())
        assertNull("AL-750mHi2tYZisNLM19Ym2Ol_000".displayableArtworkId())
        assertNull(" al-750mHi2tYZisNLM19Ym2Ol_0 ".displayableArtworkId())
    }

    @Test
    fun navidromeArtistPlaceholderIsTreatedAsMissing() {
        assertNull("ar-3rR2aWghIvOilzM2DzJ6hk_0".displayableArtworkId())
        assertNull("AR-3rR2aWghIvOilzM2DzJ6hk_000".displayableArtworkId())
    }

    @Test
    fun realAndNonNavidromeArtworkIdsArePreserved() {
        assertEquals(
            "mf-song_6a8fadce",
            "mf-song_6a8fadce".displayableArtworkId(),
        )
        assertEquals("custom-cover_0", "custom-cover_0".displayableArtworkId())
    }

    @Test
    fun missingArtworkRemainsMissing() {
        assertNull(null.displayableArtworkId())
        assertNull("   ".displayableArtworkId())
    }

    @Test
    fun albumUsesItsSongArtworkWhenSongsAreLoaded() {
        val album = Album(
            id = "album-1",
            name = "成都",
            coverArtId = "al-album-1-generated-revision",
        )
        val song = Song(
            id = "song-1",
            title = "成都",
            albumId = album.id,
            coverArtId = "mf-song-1-real-artwork",
        )

        assertEquals(
            "mf-song-1-real-artwork",
            album.withResolvedArtwork(listOf(song)).coverArtId,
        )
    }

    @Test
    fun albumFallsBackWhenAllLoadedSongsReportMissingArtwork() {
        val album = Album(
            id = "album-1",
            name = "成都",
            coverArtId = "al-album-1-generated-revision",
        )
        val song = Song(
            id = "song-1",
            title = "成都",
            albumId = album.id,
            coverArtId = "al-album-1_0",
        )

        assertNull(album.withResolvedArtwork(listOf(song)).coverArtId)
    }

    @Test
    fun albumArtworkIsPreservedUntilItsSongsAreAvailable() {
        val album = Album(
            id = "album-1",
            name = "成都",
            coverArtId = "al-album-1-real-revision",
        )

        assertEquals(
            "al-album-1-real-revision",
            album.withResolvedArtwork(emptyList()).coverArtId,
        )
    }


    @Test
    fun artistUsesRepresentativeAlbumArtworkWhenServerReturnsPlaceholder() {
        val artist = Artist(
            id = "artist-1",
            name = "林海",
            coverArtId = "ar-artist-1_0",
        )
        val album = Album(
            id = "album-1",
            name = "月光",
            artistId = artist.id,
            coverArtId = "al-album-1-real-revision",
        )

        assertEquals(
            "al-album-1-real-revision",
            listOf(artist).withResolvedArtistArtwork(listOf(album)).single().coverArtId,
        )
    }

    @Test
    fun realArtistArtworkTakesPriorityOverAlbumArtwork() {
        val artist = Artist(
            id = "artist-1",
            name = "林海",
            coverArtId = "ar-artist-1-real-revision",
        )
        val album = Album(
            id = "album-1",
            name = "月光",
            artistId = artist.id,
            coverArtId = "al-album-1-real-revision",
        )

        assertEquals(
            "ar-artist-1-real-revision",
            listOf(artist).withResolvedArtistArtwork(listOf(album)).single().coverArtId,
        )
    }

    @Test
    fun artistDetailUsesSongArtworkWhenServerReturnsPlaceholder() {
        val artist = Artist(
            id = "artist-1",
            name = "林海",
            coverArtId = "ar-artist-1_0",
        )
        val song = Song(
            id = "song-1",
            title = "月光",
            artistId = artist.id,
            coverArtId = "mf-song-1-real-artwork",
        )

        assertEquals(
            "mf-song-1-real-artwork",
            artist.withResolvedArtistArtwork(listOf(song)).coverArtId,
        )
    }
}
