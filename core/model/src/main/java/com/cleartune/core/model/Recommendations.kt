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
        val random = Random(seed)
        return listOf(
            shelf(
                "long-absent",
                "好久不见",
                "这些歌已经至少 30 天没播放了",
                songs.filter { it.lastPlayedAt != null && it.lastPlayedAt < now - 30 * day }
                    .sortedWith(compareByDescending<Song> { it.starredAt != null }.thenBy { it.lastPlayedAt }),
                20,
            ),
            shelf(
                "recently-added",
                "最近加入",
                "最近 30 天加入，没听过的排在前面",
                songs.filter { (it.createdAt ?: 0) >= now - 30 * day }
                    .sortedWith(compareBy<Song> { it.playCount > 0 }.thenByDescending { it.createdAt }),
                20,
            ),
            shelf(
                "from-favorites",
                "从收藏出发",
                "与你收藏的艺术家或流派相关",
                songs.filter {
                    it.starredAt == null && (it.artistId in favoriteArtists || it.genre in favoriteGenres)
                }.shuffled(random),
                20,
            ),
            shelf(
                "new-taste",
                "换个口味",
                "从旧曲库里挑些很少播放的歌",
                songs.filter {
                    (it.createdAt == null || it.createdAt < now - 30 * day) &&
                        it.playCount <= 2 &&
                        (it.lastPlayedAt == null || it.lastPlayedAt < now - 7 * day)
                }
                    .shuffled(random),
                20,
            ),
            shelf(
                "frequent",
                "常听精选",
                "你反复播放过的熟悉旋律",
                songs.filter {
                    it.playCount >= 3 && it.lastPlayedAt != null && it.lastPlayedAt < now - day
                }
                    .sortedWith(compareByDescending<Song> { it.starredAt != null }.thenByDescending { it.playCount }),
                20,
            ),
            shelf(
                "random",
                "随便听听",
                "从整个音乐库分散随机挑选",
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
