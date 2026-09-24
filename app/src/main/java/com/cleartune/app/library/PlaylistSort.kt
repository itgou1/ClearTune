package com.cleartune.app.library

import com.cleartune.core.model.Song
import java.text.Collator
import java.util.Locale

internal enum class PlaylistSortField(val label: String) {
    DEFAULT("默认排序"), ADDED("最近添加"), TITLE("歌曲名"), ARTIST("艺术家"),
}

internal data class PlaylistSort(
    val field: PlaylistSortField = PlaylistSortField.DEFAULT,
    val descending: Boolean = false,
) {
    fun encode() = "${field.name}:$descending"
    companion object {
        fun decode(value: String): PlaylistSort {
            val parts = value.split(':')
            val field = PlaylistSortField.entries.firstOrNull { it.name == parts.firstOrNull() }
                ?: PlaylistSortField.DEFAULT
            return PlaylistSort(field, parts.getOrNull(1)?.toBooleanStrictOrNull() ?: (field == PlaylistSortField.ADDED))
        }
    }
}

internal fun sortedFavoriteEntries(songs: List<Song>, sort: PlaylistSort): List<Song> =
    sortedPlaylistEntries(songs, songs.mapNotNull { song -> song.starredAt?.let { song.id to it } }.toMap(),
        if (sort.field == PlaylistSortField.DEFAULT) PlaylistSort(PlaylistSortField.ADDED, !sort.descending) else sort)
        .map { it.value }

/** Keep the original index for playlist removal; sorting is a local presentation only. */
internal fun sortedPlaylistEntries(
    songs: List<Song>,
    addedAt: Map<String, Long>,
    sort: PlaylistSort,
): List<IndexedValue<Song>> {
    val entries = songs.withIndex().toList()
    if (sort.field == PlaylistSortField.DEFAULT) return if (sort.descending) entries.reversed() else entries
    val collator = Collator.getInstance(Locale.CHINA).apply { strength = Collator.PRIMARY }
    return entries.sortedWith { a, b ->
        val primary = when (sort.field) {
            PlaylistSortField.ADDED -> {
                val aTime = addedAt[a.value.id] ?: a.value.createdAt
                val bTime = addedAt[b.value.id] ?: b.value.createdAt
                // Prefer playlist membership time; fall back to library creation for history.
                // Entries missing both timestamps stay last in either direction.
                if (aTime == null || bTime == null) {
                    return@sortedWith when {
                        aTime != null -> -1
                        bTime != null -> 1
                        else -> a.index.compareTo(b.index)
                    }
                }
                aTime.compareTo(bTime)
            }
            PlaylistSortField.TITLE -> collator.compare(a.value.title.trim(), b.value.title.trim())
            PlaylistSortField.ARTIST -> collator.compare(a.value.artistName.trim(), b.value.artistName.trim())
            PlaylistSortField.DEFAULT -> 0
        }
        val directed = if (sort.descending) -primary else primary
        if (directed != 0) directed
        else if (sort.field == PlaylistSortField.ARTIST) {
            collator.compare(a.value.title.trim(), b.value.title.trim()).takeIf { it != 0 }
                ?: a.index.compareTo(b.index)
        } else a.index.compareTo(b.index)
    }
}
