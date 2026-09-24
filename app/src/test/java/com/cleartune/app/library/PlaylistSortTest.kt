package com.cleartune.app.library

import com.cleartune.core.model.Song
import org.junit.Assert.*
import org.junit.Test

class PlaylistSortTest {
    @Test fun favoritesUseStarredTimeBeforeLibraryTimeAndDefaultToRecentlyFavorited() {
        val favorites = listOf(Song("a", "A", starredAt = 10, createdAt = 9999),
            Song("b", "B", starredAt = 20, createdAt = 1))
        assertEquals(listOf("b", "a"), sortedFavoriteEntries(favorites, PlaylistSort()).map { it.id })
        assertEquals(listOf("a", "b"), sortedFavoriteEntries(favorites,
            PlaylistSort(PlaylistSortField.ADDED, false)).map { it.id })
    }
    private val songs = listOf(
        Song("a", "Zebra", artistName = "Beta", createdAt = 9000),
        Song("b", "Apple", artistName = "Alpha", createdAt = 100),
        Song("c", "Banana", artistName = "Alpha", createdAt = 99999),
    )

    @Test fun recentPrefersPlaylistAdditionAndMixesLibraryFallbackInBothDirections() {
        val times = mapOf("a" to 100L, "b" to 200L)
        assertEquals(listOf("c", "b", "a"), sortedPlaylistEntries(songs, times,
            PlaylistSort(PlaylistSortField.ADDED, true)).map { it.value.id })
        assertEquals(listOf("a", "b", "c"), sortedPlaylistEntries(songs, times,
            PlaylistSort(PlaylistSortField.ADDED, false)).map { it.value.id })
        assertEquals(listOf("c", "a", "b"), sortedPlaylistEntries(songs, emptyMap(),
            PlaylistSort(PlaylistSortField.ADDED, true)).map { it.value.id })
        assertEquals(listOf("b", "a", "c"), sortedPlaylistEntries(songs, emptyMap(),
            PlaylistSort(PlaylistSortField.ADDED, false)).map { it.value.id })
    }

    @Test fun missingBothTimesStaysLastAndEqualEffectiveTimesKeepOriginalOrder() {
        val entries = listOf(Song("unknown1", "One"), Song("known", "Two", createdAt = 200),
            Song("unknown2", "Three"), Song("fallback", "Four", createdAt = 100))
        listOf(false, true).forEach { descending ->
            assertEquals(listOf("known", "fallback", "unknown1", "unknown2"),
                sortedPlaylistEntries(entries, mapOf("known" to 100L),
                    PlaylistSort(PlaylistSortField.ADDED, descending)).map { it.value.id })
        }
    }

    @Test fun alphabeticalViewKeepsServerIndexesForRemovalAndPlayback() {
        val sorted = sortedPlaylistEntries(songs, emptyMap(), PlaylistSort(PlaylistSortField.TITLE))
        assertEquals(listOf(1, 2, 0), sorted.map { it.index })
        assertEquals("b", sorted.first().value.id)
        assertEquals(listOf(0, 2, 1), sortedPlaylistEntries(songs, emptyMap(),
            PlaylistSort(PlaylistSortField.TITLE, true)).map { it.index })
    }

    @Test fun artistDirectionDoesNotReverseSongNamesWithinArtist() {
        assertEquals(listOf("a", "b", "c"), sortedPlaylistEntries(songs, emptyMap(),
            PlaylistSort(PlaylistSortField.ARTIST, true)).map { it.value.id })
    }

    @Test fun defaultAndEqualAdditionTimesPreserveOriginalOrder() {
        assertEquals(songs, sortedPlaylistEntries(songs, emptyMap(), PlaylistSort()).map { it.value })
        assertEquals(songs.reversed(), sortedPlaylistEntries(songs, emptyMap(),
            PlaylistSort(PlaylistSortField.DEFAULT, true)).map { it.value })
        assertEquals(sortedFavoriteEntries(songs, PlaylistSort(PlaylistSortField.ADDED, false)),
            sortedFavoriteEntries(songs, PlaylistSort(PlaylistSortField.DEFAULT, true)))
        assertEquals(songs, sortedPlaylistEntries(songs, songs.associate { it.id to 10L },
            PlaylistSort(PlaylistSortField.ADDED, true)).map { it.value })
    }

    @Test fun chineseTitlesUsePinyinOrder() {
        val chinese = listOf(Song("1", "中国"), Song("2", "北京"), Song("3", "上海"))
        assertEquals(listOf("北京", "上海", "中国"), sortedPlaylistEntries(chinese, emptyMap(),
            PlaylistSort(PlaylistSortField.TITLE)).map { it.value.title })
    }

    @Test fun allSettingsRoundTripAndInvalidSettingsFallBackSafely() {
        PlaylistSortField.entries.forEach { field -> listOf(false, true).forEach {
            val sort = PlaylistSort(field, it)
            assertEquals(sort, PlaylistSort.decode(sort.encode()))
        } }
        assertEquals(PlaylistSort(), PlaylistSort.decode("invalid"))
    }
}
