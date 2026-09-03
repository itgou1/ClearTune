package com.cleartune.app

import com.cleartune.core.model.Song
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class FavoriteSongsSortPolicyTest {
    private val songs = listOf(
        Song(
            id = "beta",
            title = "Beta",
            artistName = "Alpha",
            playCount = 3,
            starredAt = 100,
            createdAt = 500,
        ),
        Song(
            id = "alpha",
            title = "Alpha",
            artistName = "Zed",
            playCount = 7,
            starredAt = 300,
            createdAt = 100,
        ),
        Song(
            id = "gamma",
            title = "Gamma",
            artistName = "Able",
            playCount = 1,
            starredAt = 200,
            createdAt = 900,
        ),
    )

    @Test
    fun supportsEveryFavoriteSongSortOrder() {
        assertOrder(FavoriteSongSort.TITLE, "alpha", "beta", "gamma")
        assertOrder(FavoriteSongSort.RECENTLY_FAVORITED, "alpha", "gamma", "beta")
        assertOrder(FavoriteSongSort.ARTIST, "gamma", "beta", "alpha")
        assertOrder(FavoriteSongSort.PLAY_COUNT, "alpha", "beta", "gamma")
        assertOrder(FavoriteSongSort.RECENTLY_ADDED, "gamma", "beta", "alpha")
    }

    private fun assertOrder(sort: FavoriteSongSort, vararg expectedIds: String) {
        assertEquals(
            expectedIds.toList(),
            sortFavoriteSongs(songs, sort, Locale.ENGLISH).map(Song::id),
        )
    }
}
