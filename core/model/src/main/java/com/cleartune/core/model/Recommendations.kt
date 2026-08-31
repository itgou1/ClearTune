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
        val recentlyAdded = songs.filter { (it.createdAt ?: 0) >= now - 30 * day }
            .sortedWith(compareBy<Song> { it.playCount > 0 }.thenByDescending { it.createdAt })
        val fromFavorites = songs.filter {
            it.starredAt == null && (it.artistId in favoriteArtists || it.genre in favoriteGenres)
        }.shuffled(random)
        val newTaste = songs.filter {
            (it.createdAt == null || it.createdAt < now - 30 * day) &&
                it.playCount <= 2 &&
                (it.lastPlayedAt == null || it.lastPlayedAt < now - 7 * day)
        }.shuffled(random)
        val frequent = songs.filter {
            it.playCount >= 3 && it.lastPlayedAt != null && it.lastPlayedAt < now - day
        }.sortedWith(compareByDescending<Song> { it.starredAt != null }.thenByDescending { it.playCount })
        val selectedSongIds = mutableSetOf<String>()
        fun uniqueShelf(
            id: String,
            title: String,
            reason: String,
            candidates: List<Song>,
            size: Int,
        ): RecommendationShelf = shelf(
            id = id,
            title = title,
            reason = reason,
            candidates = candidates.filterNot { it.id in selectedSongIds },
            size = size,
        ).also { result -> selectedSongIds += result.songs.map(Song::id) }

        return listOf(
            uniqueShelf(
                "long-absent",
                "好久不见",
                "至少 30 天没播放，从 ${longAbsent.size} 首旧爱中挑选",
                longAbsent,
                20,
            ),
            uniqueShelf(
                "recently-added",
                "最近加入",
                "近 30 天加入的 ${recentlyAdded.size} 首歌，没听过的优先",
                recentlyAdded,
                20,
            ),
            uniqueShelf(
                "from-favorites",
                "从喜欢出发",
                favoriteSignals.takeIf(List<String>::isNotEmpty)
                    ?.joinToString(prefix = "因为你喜欢 ", separator = "、")
                    ?: "与你收藏的艺术家或流派相关",
                fromFavorites,
                20,
            ),
            uniqueShelf(
                "new-taste",
                "换个口味",
                "播放不超过 2 次，并避开最近 7 天听过的歌",
                newTaste,
                20,
            ),
            uniqueShelf(
                "frequent",
                "常听精选",
                "播放至少 3 次，并避开今天刚听过的歌",
                frequent,
                20,
            ),
            uniqueShelf(
                "random",
                "随便听听",
                "从全曲库随机抽取，每位艺术家最多 2 首",
                songs.shuffled(random),
                30,
            ),
        ).filter { it.songs.isNotEmpty() }
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

private val metadataIdPattern = Regex("^\\d+(?:[_:/-]\\d+)+$")

private fun isMetadataId(value: String): Boolean = metadataIdPattern.matches(value.trim())
