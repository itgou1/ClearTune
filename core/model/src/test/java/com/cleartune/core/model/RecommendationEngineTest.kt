package com.cleartune.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationEngineTest {
    private val songs = (1..60).map { index ->
        Song(
            id = "song-$index",
            title = "歌曲 $index",
            artistId = "artist-${index % 12}",
            artistName = "艺术家 ${index % 12}",
            genre = listOf("摇滚", "爵士", "古典")[index % 3],
            playCount = (index % 7).toLong(),
            starredAt = if (index % 11 == 0) 1 else null,
            createdAt = NOW - (index % 40) * DAY,
            lastPlayedAt = if (index % 4 == 0) NOW - index * DAY else null,
        )
    }

    @Test
    fun fixedSeedIsStableAndQueueIsExcluded() {
        val engine = RecommendationEngine()
        val first = engine.generate(songs, 42, setOf("song-1", "song-2"), NOW)
        val second = engine.generate(songs, 42, setOf("song-1", "song-2"), NOW)

        assertEquals(first, second)
        assertTrue(first.flatMap { it.songs }.none { it.id == "song-1" || it.id == "song-2" })
    }

    @Test
    fun eachShelfLimitsAnArtistToTwoSongs() {
        RecommendationEngine().generate(songs, 7, now = NOW).forEach { shelf ->
            val maximum = shelf.songs.groupingBy { it.artistId }.eachCount().values.maxOrNull() ?: 0
            assertTrue("${shelf.title} artist quota", maximum <= 2)
        }
    }

    @Test
    fun smallLibraryFallsBackToSingleHonestShelf() {
        val result = RecommendationEngine().generate(songs.take(8), 5, now = NOW)
        assertEquals(listOf("随便听听"), result.map { it.title })
    }

    @Test
    fun explorationExcludesNewMusicAndFrequentRequiresPlaybackHistory() {
        val result = RecommendationEngine().generate(songs, 9, now = NOW)
        val recentlyAdded = result.first { it.id == "recently-added" }.songs.map(Song::id).toSet()
        val newTaste = result.first { it.id == "new-taste" }.songs
        val frequent = result.first { it.id == "frequent" }.songs

        assertTrue(newTaste.none { it.id in recentlyAdded })
        assertTrue(newTaste.all { it.createdAt == null || it.createdAt < NOW - 30 * DAY })
        assertTrue(frequent.all { it.playCount >= 3 && it.lastPlayedAt != null })
    }

    private companion object {
        const val DAY = 24 * 60 * 60 * 1_000L
        const val NOW = 1_800_000_000_000L
    }
}
