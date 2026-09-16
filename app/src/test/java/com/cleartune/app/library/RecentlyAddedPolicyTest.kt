package com.cleartune.app.library

import com.cleartune.core.model.Album
import com.cleartune.core.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentlyAddedPolicyTest {
    private val day = 24L * 60 * 60 * 1_000
    private val now = 100 * day

    @Test fun addingSongToOldAlbumMovesItAheadOfNewAlbum() {
        val albums = listOf(album("new", 90 * day), album("old", day))
        val songs = listOf(song("original", "old", day), song("new-track", "new", 90 * day))
        assertEquals(listOf("new", "old"), orderedIds(albums, songs))
        assertEquals(
            listOf("old", "new"),
            orderedIds(albums, songs + song("extra", "old", now)),
        )
        assertEquals(listOf("new", "old"), albums.map { it.id })
        assertEquals(day, albums.last().createdAt)
    }

    @Test fun songDateTakesPrecedenceOverAlbumDate() {
        assertEquals(
            listOf("b", "a"),
            orderedIds(
                listOf(album("a", now), album("b", day)),
                listOf(song("a1", "a", 50 * day), song("b1", "b", 60 * day)),
            ),
        )
    }

    @Test fun metadataAndPlaybackChangesDoNotMoveAlbum() {
        val albums = listOf(album("a", day), album("b", 2 * day))
        val tracks = listOf(song("a1", "a", 80 * day), song("b1", "b", 90 * day))
        assertEquals(
            orderedIds(albums, tracks),
            orderedIds(
                albums.map { it.copy(name = "Changed", coverArtId = "new-cover", starredAt = now) },
                tracks.map { it.copy(title = "Edited", playCount = 10, lastPlayedAt = now, starredAt = now) },
            ),
        )
    }

    @Test fun missingInvalidOrFutureSongDatesFallBackToAlbumDate() {
        val albums = listOf(album("a", 70 * day), album("b", 80 * day), album("c", null))
        assertEquals(
            listOf("b", "a", "c"),
            orderedIds(albums, listOf(
                song("a1", "a", null), song("a2", "a", now + day),
                song("b1", "b", 0), song("c1", "c", -1),
                song("unrelated", "unknown", now), song("unassigned", null, now),
            )),
        )
    }

    @Test fun sameNamedAlbumsAreMatchedByIdNotTitle() {
        assertEquals(
            listOf("b", "a"),
            orderedIds(listOf(album("a", day), album("b", day)).map { it.copy(name = "Same") },
                listOf(song("extra", "b", now))),
        )
    }

    @Test fun tiesAreStableAndShelfIsLimitedAfterSorting() {
        val albums = (1..25).map { album(it.toString().padStart(2, '0'), it * day) }
        assertEquals((25 downTo 6).map { it.toString().padStart(2, '0') }, orderedIds(albums, emptyList()))
        val ties = listOf(album("b", null), album("a", null)).map { it.copy(name = "Same") }
        assertEquals(listOf("a", "b"), orderedIds(ties, emptyList()))
        assertEquals(emptyList<String>(), orderedIds(emptyList(), emptyList()))
    }

    @Test fun highlightIncludesThirtyDayBoundaryButNotOldUnknownOrFutureSongs() {
        val songs = listOf(
            song("today", "a", now), song("boundary", "a", now - 30 * day),
            song("old", "a", now - 30 * day - 1), song("missing", "a", null),
            song("zero", "a", 0), song("future", "a", now + 1),
        )
        assertEquals(setOf("today", "boundary"), recentlyAddedSongIds(songs, now))
        assertEquals(setOf("today"), recentlyAddedSongIds(songs.take(3), now + 1))
        assertEquals(emptySet<String>(), recentlyAddedSongIds(emptyList(), now))
    }

    @Test fun removingNewestSongRestoresOrderingAndDoesNotReorderTracks() {
        val songs = listOf(song("old-track", "a", day), song("new-track", "a", now))
        val albums = listOf(album("a", day), album("b", 90 * day))
        assertEquals(listOf("a", "b"), orderedIds(albums, songs))
        assertEquals(listOf("b", "a"), orderedIds(albums, songs.take(1)))
        recentlyAddedSongIds(songs, now)
        assertEquals(listOf("old-track", "new-track"), songs.map { it.id })
    }

    private fun album(id: String, createdAt: Long?) = Album(id, id, createdAt = createdAt)
    private fun song(id: String, albumId: String?, createdAt: Long?) =
        Song(id, id, albumId = albumId, createdAt = createdAt)
    private fun orderedIds(albums: List<Album>, songs: List<Song>) =
        recentlyAddedAlbums(albums, songs, now).map { it.id }
}
