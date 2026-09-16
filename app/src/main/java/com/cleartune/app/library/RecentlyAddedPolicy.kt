package com.cleartune.app.library

import com.cleartune.core.model.Album
import com.cleartune.core.model.Song

private const val RECENT_ADDITION_WINDOW_MS = 30L * 24 * 60 * 60 * 1_000

/** Home-only ordering; never replace album creation dates or the library's ordering. */
internal fun recentlyAddedAlbums(
    albums: List<Album>,
    songs: List<Song>,
    now: Long = System.currentTimeMillis(),
): List<Album> {
    val latestSongDates = mutableMapOf<String, Long>()
    for (song in songs) {
        val albumId = song.albumId?.takeIf { it.isNotBlank() } ?: continue
        val createdAt = song.createdAt.validAdditionTime(now) ?: continue
        latestSongDates[albumId] = maxOf(latestSongDates[albumId] ?: 0L, createdAt)
    }
    return albums.sortedWith(
        compareByDescending<Album> {
            latestSongDates[it.id] ?: it.createdAt.validAdditionTime(now) ?: 0L
        }.thenComparator { left, right ->
            left.name.compareTo(right.name, ignoreCase = true)
        }.thenBy { it.id },
    ).take(20)
}

internal fun recentlyAddedSongIds(
    songs: List<Song>,
    now: Long = System.currentTimeMillis(),
): Set<String> = songs.asSequence().filter { song ->
    val createdAt = song.createdAt.validAdditionTime(now)
    createdAt != null && createdAt >= now - RECENT_ADDITION_WINDOW_MS
}.map { it.id }.toSet()

private fun Long?.validAdditionTime(now: Long): Long? = this?.takeIf { it > 0 && it <= now }
