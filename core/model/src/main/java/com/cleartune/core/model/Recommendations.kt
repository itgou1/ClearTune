package com.cleartune.core.model

import kotlin.random.Random

data class RecommendationShelf(
    val id: String,
    val title: String,
    val reason: String,
    val songs: List<Song>,
)

class RecommendationEngine {
    fun generate(
        library: List<Song>,
        seed: Long,
        excludedSongIds: Set<String> = emptySet(),
        now: Long = System.currentTimeMillis(),
    ): List<RecommendationShelf> {
        val songs = library.filterNot { it.id in excludedSongIds }
        if (songs.isEmpty()) return emptyList()
        if (songs.size < 20) {
            return listOf(
                shelf(
                    id = "random",
                    title = "随便听听",
                    reason = "从你的音乐库随机挑选",
                    candidates = songs.shuffled(Random(seed)),
                    size = 30,
                ),
            )
        }
        val day = 24 * 60 * 60 * 1_000L
        val favoriteArtists = songs.filter { it.starredAt != null }.mapNotNull(Song::artistId).toSet()
        val favoriteGenres = songs.filter { it.starredAt != null }.mapNotNull(Song::genre).toSet()
        val favoriteSignals = songs.filter { it.starredAt != null }
            .flatMap { song -> listOfNotNull(song.artistName.takeIf(String::isNotBlank), song.genre?.takeUnless(::isMetadataId)) }
            .groupingBy(String::trim)
            .eachCount()
            .entries
            .sortedByDescending(Map.Entry<String, Int>::value)
            .map(Map.Entry<String, Int>::key)
            .distinct()
            .take(2)
        val random = Random(seed)
        val longAbsent = songs.filter { it.lastPlayedAt != null && it.lastPlayedAt < now - 30 * day }
            .sortedWith(compareByDescending<Song> { it.starredAt != null }.thenBy { it.lastPlayedAt })
        val lessRecentlyPlayed = songs.filter { it.lastPlayedAt != null && it.lastPlayedAt < now - 14 * day }
            .sortedBy { it.lastPlayedAt }
        val playedSongsByOldest = songs.filter { it.lastPlayedAt != null }
            .sortedBy { it.lastPlayedAt }
        val recentlyAdded = songs.filter { (it.createdAt ?: 0) >= now - 30 * day }
            .sortedWith(compareBy<Song> { it.playCount > 0 }.thenByDescending { it.createdAt })
        val fromFavorites = songs.filter {
            it.starredAt == null && (it.artistId in favoriteArtists || it.genre in favoriteGenres)
        }.shuffled(random)
        val favoriteFallback = songs.filter {
            it.artistId in favoriteArtists || it.genre in favoriteGenres
        }.shuffled(random)
        val newTaste = songs.filter {
            (it.createdAt == null || it.createdAt < now - 30 * day) &&
                it.playCount <= 2 &&
                (it.lastPlayedAt == null || it.lastPlayedAt < now - 7 * day)
        }.shuffled(random)
        val relaxedNewTaste = songs.filter {
            (it.createdAt == null || it.createdAt < now - 30 * day) &&
                it.playCount <= 5 &&
                (it.lastPlayedAt == null || it.lastPlayedAt < now - 3 * day)
        }.shuffled(random)
        val leastPlayed = songs.filter { it.createdAt == null || it.createdAt < now - 30 * day }
            .sortedWith(compareBy<Song> { it.playCount }.thenBy { it.lastPlayedAt ?: Long.MIN_VALUE })
        val frequent = songs.filter {
            it.playCount >= 3 && it.lastPlayedAt != null && it.lastPlayedAt < now - day
        }.sortedWith(compareByDescending<Song> { it.starredAt != null }.thenByDescending { it.playCount })

        val fromFavoritesPlan = DiscoveryPlan(
            id = "from-favorites",
            title = "从喜欢出发",
            reason = favoriteSignals.takeIf(List<String>::isNotEmpty)
                ?.joinToString(prefix = "因为你喜欢 ", separator = "、")
                ?: "收藏一些歌曲后，会从相似音乐中推荐",
            candidates = (fromFavorites + favoriteFallback).distinctBy(Song::id),
        )
        val newTastePlan = DiscoveryPlan(
            id = "new-taste",
            title = "换个口味",
            reason = "优先选择播放不超过 2 次、最近少听的歌曲",
            candidates = (newTaste + relaxedNewTaste + leastPlayed).distinctBy(Song::id),
        )
        val longAbsentPlan = DiscoveryPlan(
            id = "long-absent",
            title = "好久不见",
            reason = "优先从最久没听的歌曲中挑选",
            candidates = (longAbsent + lessRecentlyPlayed + playedSongsByOldest).distinctBy(Song::id),
        )
        val discoveryPlans = listOf(fromFavoritesPlan, newTastePlan, longAbsentPlan)
        val discoverySongs = discoveryPlans.associate { it.id to mutableListOf<Song>() }
        val selectedSongIds = mutableSetOf<String>()

        fun addSongs(plan: DiscoveryPlan, targetSize: Int) {
            val selected = checkNotNull(discoverySongs[plan.id])
            val artistCounts = selected.groupingBy { it.artistId ?: it.artistName }.eachCount().toMutableMap()
            plan.candidates.forEach { song ->
                if (selected.size >= targetSize) return
                if (song.id in selectedSongIds) return@forEach
                val artist = song.artistId ?: song.artistName
                if (artistCounts.getOrDefault(artist, 0) >= 2) return@forEach
                selected += song
                selectedSongIds += song.id
                artistCounts[artist] = artistCounts.getOrDefault(artist, 0) + 1
            }
        }

        // Reserve a small base for the more constrained themes before filling the broad first shelf.
        listOf(longAbsentPlan, newTastePlan, fromFavoritesPlan).forEach { plan ->
            addSongs(plan, DISCOVERY_RESERVATION_SIZE)
        }
        discoveryPlans.forEach { plan -> addSongs(plan, DISCOVERY_SHELF_SIZE) }
        val discoveryShelves = discoveryPlans.map { plan ->
            RecommendationShelf(
                id = plan.id,
                title = plan.title,
                reason = plan.reason,
                songs = checkNotNull(discoverySongs[plan.id]),
            )
        }

        return discoveryShelves + listOf(
            shelf(
                "recently-added",
                "最近加入",
                "近 30 天加入的 ${recentlyAdded.size} 首歌，没听过的优先",
                recentlyAdded,
                20,
            ),
            shelf(
                "frequent",
                "常听精选",
                "播放至少 3 次，并避开今天刚听过的歌",
                frequent,
                20,
            ),
            shelf(
                "random",
                "随便听听",
                "从全曲库随机抽取，每位艺术家最多 2 首",
                songs.shuffled(random),
                30,
            ),
        ).filter { it.id in DISCOVERY_SHELF_IDS || it.songs.isNotEmpty() }
    }

    private fun shelf(
        id: String,
        title: String,
        reason: String,
        candidates: List<Song>,
        size: Int,
    ): RecommendationShelf {
        val artistCounts = mutableMapOf<String, Int>()
        val selected = candidates.filter { song ->
            val artist = song.artistId ?: song.artistName
            val allowed = artistCounts.getOrDefault(artist, 0) < 2
            if (allowed) artistCounts[artist] = artistCounts.getOrDefault(artist, 0) + 1
            allowed
        }.take(size)
        return RecommendationShelf(id, title, reason, selected)
    }
}

private data class DiscoveryPlan(
    val id: String,
    val title: String,
    val reason: String,
    val candidates: List<Song>,
)

private val DISCOVERY_SHELF_IDS = setOf("from-favorites", "new-taste", "long-absent")
private const val DISCOVERY_RESERVATION_SIZE = 2
private const val DISCOVERY_SHELF_SIZE = 20

private val metadataIdPattern = Regex("^\\d+(?:[_:/-]\\d+)+$")

private fun isMetadataId(value: String): Boolean = metadataIdPattern.matches(value.trim())
