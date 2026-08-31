package com.cleartune.app

import com.cleartune.core.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningProfileTest {
    @Test
    fun profileRanksPlaybackSignalsAndCalculatesHabits() {
        val now = 2_000_000_000_000L
        val songs = listOf(
            Song(
                id = "one",
                title = "One",
                artistName = "Artist A",
                durationSeconds = 240,
                year = 2018,
                genre = "Rock",
                playCount = 8,
                lastPlayedAt = now - 1_000,
                starredAt = now - 2_000,
            ),
            Song(
                id = "two",
                title = "Two",
                artistName = "Artist B",
                durationSeconds = 180,
                year = 2023,
                genre = "Jazz",
                playCount = 2,
                lastPlayedAt = now - 10_000,
            ),
            Song(id = "three", title = "Three", artistName = "Artist C"),
        )

        val stats = analyzeListeningProfile(songs, now)

        assertEquals(10L, stats.totalPlays)
        assertEquals(38L, stats.estimatedMinutes)
        assertEquals(2, stats.listenedSongCount)
        assertEquals("摇滚", stats.topGenres.first().label)
        assertEquals("Artist A", stats.topArtists.first().label)
        assertEquals("one", stats.topSongs.first().id)
        assertTrue(stats.repeatRate > 0)
        assertTrue(stats.hasSignal)
    }

    @Test
    fun emptyProfileWaitsForListeningSignals() {
        val stats = analyzeListeningProfile(
            songs = listOf(Song(id = "new", title = "New")),
            now = 2_000_000_000_000L,
        )

        assertEquals(ListenerPersona.BEGINNING, stats.persona)
        assertTrue(!stats.hasSignal)
    }

    @Test
    fun profileIgnoresMetadataIdentifiersAndMergesGenreAliases() {
        val stats = analyzeListeningProfile(
            songs = listOf(
                Song(id = "pop-en", title = "One", genre = "Pop", playCount = 2),
                Song(id = "pop-zh", title = "Two", genre = "流行", playCount = 3),
                Song(id = "metadata", title = "Three", genre = "106212_10497", playCount = 20),
            ),
        )

        assertEquals(listOf("流行"), stats.topGenres.map(ListeningPreference::label))
        assertEquals(5L, stats.topGenres.single().score)
    }
}
