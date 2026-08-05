package com.cleartune.core.database.model

import com.cleartune.core.model.Album
import com.cleartune.core.model.AlbumId
import com.cleartune.core.model.Artist
import com.cleartune.core.model.ArtistId
import com.cleartune.core.model.LocationId
import com.cleartune.core.model.SourceId
import com.cleartune.core.model.TrackId
import com.cleartune.core.model.TrackSummary
import java.nio.charset.StandardCharsets
import java.util.UUID

private const val ARTIST_SEPARATOR = '\u001f'

data class LibraryTrackRow(
    val trackId: String,
    val title: String,
    val albumId: String? = null,
    val albumTitle: String?,
    val artistNames: String?,
    val artistIds: String? = null,
    val artworkRef: String?,
    val durationMs: Long?,
    val downloadedLocations: Int,
    val sourceIds: String = "",
    val relativeFolders: String = "",
    val addedAtEpochMs: Long = 0,
)

fun LibraryTrackRow.hasArtist(artistId: ArtistId): Boolean = artistIds
    ?.split(ARTIST_SEPARATOR)
    ?.any { it == artistId.value }
    ?: false

fun LibraryTrackRow.belongsToFolder(relativeFolder: String): Boolean = relativeFolders
    .split(ARTIST_SEPARATOR)
    .any { it == relativeFolder }

fun LibraryTrackRow.toTrackSummary(): TrackSummary = TrackSummary(
    id = TrackId(trackId),
    title = title,
    albumTitle = albumTitle,
    artistNames = artistNames
        ?.split(ARTIST_SEPARATOR)
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        .orEmpty(),
    artworkRef = artworkRef,
    durationMs = durationMs,
    downloaded = downloadedLocations > 0,
)

data class AlbumRow(
    val albumId: String,
    val title: String,
    val artworkRef: String?,
)

fun AlbumRow.toDomain(): Album = Album(AlbumId(albumId), title, artworkRef)

data class ArtistRow(
    val artistId: String,
    val name: String,
)

fun ArtistRow.toDomain(): Artist = Artist(ArtistId(artistId), name)

data class FolderRow(
    val relativeFolder: String,
    val trackCount: Int,
    val sourceName: String,
)

data class MediaCatalogRow(
    val mediaId: String,
    val title: String,
    val albumTitle: String?,
    val artistNames: String?,
    val artworkUri: String?,
    val playbackUri: String,
    val sourceId: String,
    val locationId: String,
)

data class MediaCatalogNodeRow(
    val mediaId: String,
    val title: String,
    val artworkUri: String?,
)

data class LibraryIngestRecord(
    val sourceKey: String,
    val uri: String,
    val displayName: String,
    val relativeFolder: String,
    val title: String,
    val albumTitle: String?,
    val artistNames: List<String>,
    val durationMs: Long?,
    val artworkRef: String?,
    val sizeBytes: Long,
    val modifiedEpochSeconds: Long,
    val addedAtEpochMs: Long,
)

object StableLibraryId {
    fun track(sourceId: SourceId, sourceKey: String): TrackId = TrackId(stable("track", sourceId, sourceKey))
    fun location(sourceId: SourceId, sourceKey: String): LocationId = LocationId(stable("location", sourceId, sourceKey))
    fun album(sourceId: SourceId, title: String): AlbumId = AlbumId(stable("album", sourceId, title.trim().lowercase()))
    fun artist(sourceId: SourceId, name: String): ArtistId = ArtistId(stable("artist", sourceId, name.trim().lowercase()))

    private fun stable(kind: String, sourceId: SourceId, key: String): String = UUID.nameUUIDFromBytes(
        "$kind:${sourceId.value}:$key".toByteArray(StandardCharsets.UTF_8),
    ).toString()
}
