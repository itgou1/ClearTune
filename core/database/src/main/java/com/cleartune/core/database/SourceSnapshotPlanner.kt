package com.cleartune.core.database

import com.cleartune.core.database.entity.TrackEntity
import com.cleartune.core.database.entity.TrackLocationEntity
import com.cleartune.core.database.model.LibraryIngestRecord
import com.cleartune.core.model.LocationType

data class SourceSnapshotWritePlan(
    val track: TrackEntity,
    val location: TrackLocationEntity,
    val requiresWrite: Boolean,
    val albumRequiresWrite: Boolean,
)

object SourceSnapshotPlanner {
    fun plan(
        existingTrack: TrackEntity,
        existingLocation: TrackLocationEntity,
        existingArtistNames: List<String>,
        desiredAlbumId: String?,
        incoming: LibraryIngestRecord,
        existingAlbumTitle: String? = incoming.albumTitle?.trim(),
    ): SourceSnapshotWritePlan {
        val desiredTrack = existingTrack.copy(
            title = incoming.title,
            durationMs = incoming.durationMs,
            albumId = desiredAlbumId,
            artworkRef = incoming.artworkRef,
        )
        val desiredLocation = existingLocation.copy(
            type = LocationType.LOCAL_URI.name,
            uri = incoming.uri,
            available = true,
            sizeBytes = incoming.sizeBytes,
            etag = null,
            relativeFolder = incoming.relativeFolder,
            displayName = incoming.displayName,
            modifiedEpochSeconds = incoming.modifiedEpochSeconds,
        )
        val albumRequiresWrite = incoming.albumTitle?.trim() != existingAlbumTitle
        val artistsChanged = normalizedArtists(existingArtistNames) != normalizedArtists(incoming.artistNames)
        return SourceSnapshotWritePlan(
            track = desiredTrack,
            location = desiredLocation,
            requiresWrite = existingTrack != desiredTrack ||
                existingLocation != desiredLocation ||
                artistsChanged ||
                albumRequiresWrite,
            albumRequiresWrite = albumRequiresWrite,
        )
    }

    private fun normalizedArtists(names: List<String>): List<String> = names
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map(String::lowercase)
        .distinct()
        .sorted()
}

fun missingSourceKeys(existing: List<String>, retained: Collection<String>): Set<String> {
    if (existing.isEmpty()) return emptySet()
    val retainedSet = retained.toHashSet()
    return existing.filterTo(linkedSetOf()) { it !in retainedSet }
}

fun ftsMatchQuery(userText: String): String? = userText
    .trim()
    .split(Regex("\\s+"))
    .filter(String::isNotBlank)
    .takeIf(List<String>::isNotEmpty)
    ?.joinToString(" AND ") { term -> "\"${term.replace("\"", "\"\"")}\"*" }
