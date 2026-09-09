package com.cleartune.app.library

import com.cleartune.core.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistSongPickerPolicyTest {
    private val songs = listOf(
        Song("existing", "已有", starredAt = 1, lastPlayedAt = 400),
        Song("older", "晴天", artistName = "周杰伦", albumName = "叶惠美", starredAt = 1, lastPlayedAt = 100),
        Song("newer", "夜曲", artistName = "周杰伦", albumName = "十一月的萧邦", lastPlayedAt = 300),
        Song("unplayed", "A Little Love", artistName = "冯曦妤", starredAt = 2),
    )

    @Test fun existingSongsAreExcludedFromEveryFilterAndSearch() {
        val available = playlistSongCandidates(songs + songs.first(), setOf("existing"))
        assertEquals(3, available.size)
        PlaylistSongFilter.entries.forEach { filter ->
            assertTrue(filterPlaylistSongCandidates(available, filter, "").none { it.id == "existing" })
            assertTrue(filterPlaylistSongCandidates(available, filter, "已有").isEmpty())
        }
    }

    @Test fun favoritesAndRecentlyPlayedUseTheirOwnSignals() {
        val available = playlistSongCandidates(songs, setOf("existing"))
        assertEquals(setOf("older", "unplayed"), filterPlaylistSongCandidates(available, PlaylistSongFilter.FAVORITES, "").map(Song::id).toSet())
        assertEquals(listOf("newer", "older"), filterPlaylistSongCandidates(available, PlaylistSongFilter.RECENT, "").map(Song::id))
    }

    @Test fun searchMatchesTitleArtistAndAlbumWithAllTermsRequired() {
        assertEquals(listOf("older"), filterPlaylistSongCandidates(songs, PlaylistSongFilter.ALL, "周杰伦 叶惠美").map(Song::id))
        assertEquals(listOf("unplayed"), filterPlaylistSongCandidates(songs, PlaylistSongFilter.ALL, " LITTLE  love ").map(Song::id))
        assertTrue(filterPlaylistSongCandidates(songs, PlaylistSongFilter.FAVORITES, "夜曲").isEmpty())
    }
}
