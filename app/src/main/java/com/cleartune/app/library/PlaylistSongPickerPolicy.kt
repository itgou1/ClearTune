package com.cleartune.app.library

import com.cleartune.core.model.Song
import java.text.Collator
import java.util.Locale

internal enum class PlaylistSongFilter { ALL, FAVORITES, RECENT }

internal fun playlistSongCandidates(songs: List<Song>, existingIds: Set<String>): List<Song> =
    songs.distinctBy(Song::id).filterNot { it.id in existingIds }

internal fun filterPlaylistSongCandidates(
    songs: List<Song>,
    filter: PlaylistSongFilter,
    query: String,
): List<Song> {
    val terms = normalizeSearchText(query).split(' ').filter(String::isNotBlank)
    val collator = Collator.getInstance(Locale.CHINA)
    val titleOrder = Comparator<Song> { first, second -> collator.compare(first.title, second.title) }
        .thenBy(Song::id)
    val filtered = songs.filter { song ->
        val matchesFilter = when (filter) {
            PlaylistSongFilter.ALL -> true
            PlaylistSongFilter.FAVORITES -> song.starredAt != null
            PlaylistSongFilter.RECENT -> (song.lastPlayedAt ?: 0L) > 0L
        }
        matchesFilter && (terms.isEmpty() || normalizeSearchText(
            listOf(song.title, song.artistName, song.albumName).joinToString(" "),
        ).let { text -> terms.all(text::contains) })
    }
    return if (filter == PlaylistSongFilter.RECENT) {
        filtered.sortedWith(compareByDescending<Song> { it.lastPlayedAt }.then(titleOrder))
    } else {
        filtered.sortedWith(titleOrder)
    }
}
