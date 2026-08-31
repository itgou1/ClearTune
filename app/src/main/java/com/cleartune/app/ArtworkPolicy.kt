package com.cleartune.app

import com.cleartune.core.model.Album
import com.cleartune.core.model.Artist
import com.cleartune.core.model.Song

/**
 * Older Navidrome versions may expose an album artwork id even when the album has no image, then
 * return Navidrome's blue-record placeholder from getCoverArt. A zero revision on an album id is
 * how that legacy response appears in the library data. Treat it as missing so ClearTune can use
 * its own deterministic fallback artwork instead.
 */
private val navidromeMissingGeneratedArtwork = Regex(
    pattern = "^(?:al|ar)-[^_]+_0+(?:[?#].*)?$",
    option = RegexOption.IGNORE_CASE,
)

internal fun String?.displayableArtworkId(): String? = this
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?.takeUnless(navidromeMissingGeneratedArtwork::matches)

/**
 * Navidrome may give an album a non-zero artwork revision even though getCoverArt still returns
 * its blue-record placeholder. Song rows are a better signal: when a real album image exists,
 * Navidrome also exposes a displayable artwork id on at least one song in that album.
 *
 * Only apply this reconciliation when songs for an album are present. That keeps partial search or
 * artist responses from discarding an album image before their song data has been loaded.
 */
internal fun List<Album>.withResolvedArtwork(songs: List<Song>): List<Album> {
    if (isEmpty() || songs.isEmpty()) return this

    val albumIdsWithSongs = HashSet<String>()
    val songArtworkByAlbumId = LinkedHashMap<String, String>()
    songs.forEach { song ->
        val albumId = song.albumId ?: return@forEach
        albumIdsWithSongs += albumId
        song.coverArtId.displayableArtworkId()?.let { artworkId ->
            songArtworkByAlbumId.putIfAbsent(albumId, artworkId)
        }
    }

    return map { album ->
        if (album.id !in albumIdsWithSongs) {
            album.copy(coverArtId = album.coverArtId.displayableArtworkId())
        } else {
            // A loaded album whose songs all report missing artwork is the false-positive case
            // where Navidrome serves its generated blue-record placeholder for the album id.
            album.copy(coverArtId = songArtworkByAlbumId[album.id])
        }
    }
}

internal fun Album.withResolvedArtwork(songs: List<Song>): Album =
    listOf(this).withResolvedArtwork(songs).single()

/**
 * Navidrome commonly returns an `ar-…_0` generated placeholder for every artist. Prefer the
 * artist's own real image, then a real image from one of their albums.
 */
internal fun List<Artist>.withResolvedArtistArtwork(albums: List<Album>): List<Artist> {
    if (isEmpty()) return this

    val albumArtworkByArtistId = LinkedHashMap<String, String>()
    albums.forEach { album ->
        val artistId = album.artistId ?: return@forEach
        album.coverArtId.displayableArtworkId()?.let { artworkId ->
            albumArtworkByArtistId.putIfAbsent(artistId, artworkId)
        }
    }

    return map { artist ->
        artist.copy(
            coverArtId = artist.coverArtId.displayableArtworkId()
                ?: albumArtworkByArtistId[artist.id],
        )
    }
}

internal fun Artist.withResolvedArtistArtwork(songs: List<Song>): Artist = copy(
    coverArtId = coverArtId.displayableArtworkId()
        ?: songs.firstNotNullOfOrNull { it.coverArtId.displayableArtworkId() },
)
