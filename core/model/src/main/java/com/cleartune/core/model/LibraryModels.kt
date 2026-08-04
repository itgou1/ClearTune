package com.cleartune.core.model

enum class SourceType { LOCAL, WEBDAV }
enum class LocationType { LOCAL_URI, REMOTE_URL, DOWNLOADED_FILE }
enum class SongSort { TITLE, ARTIST, ALBUM, DATE_ADDED, DURATION }

data class MusicSource(
    val id: SourceId,
    val name: String,
    val type: SourceType,
    val baseUrl: String? = null,
    val allowCleartext: Boolean = false,
    val credentialAlias: CredentialAlias? = null,
    val enabled: Boolean = true,
    val lastSyncedAtEpochMs: Long? = null,
) {
    init {
        require(name.isNotBlank())
        require(type == SourceType.WEBDAV || baseUrl == null)
        require(type == SourceType.WEBDAV || credentialAlias == null)
    }
}

data class Track(
    val id: TrackId,
    val title: String,
    val durationMs: Long? = null,
    val albumId: AlbumId? = null,
    val artworkRef: String? = null,
    val addedAtEpochMs: Long = 0,
    val albumTitle: String? = null,
    val artistNames: List<String> = emptyList(),
    val artworkResolved: Boolean = false,
) {
    init {
        require(title.isNotBlank())
        require(durationMs == null || durationMs >= 0)
    }
}

data class TrackLocation(
    val id: LocationId,
    val trackId: TrackId,
    val sourceId: SourceId,
    val sourceKey: String,
    val type: LocationType,
    val uri: String,
    val available: Boolean = true,
    val sizeBytes: Long? = null,
    val etag: String? = null,
) {
    init {
        require(sourceKey.isNotBlank())
        require(uri.isNotBlank())
        require(sizeBytes == null || sizeBytes >= 0)
    }
}

data class Album(
    val id: AlbumId,
    val title: String,
    val artworkRef: String? = null,
) { init { require(title.isNotBlank()) } }

data class Artist(val id: ArtistId, val name: String) {
    init { require(name.isNotBlank()) }
}

data class TrackSummary(
    val id: TrackId,
    val title: String,
    val albumTitle: String? = null,
    val artistNames: List<String> = emptyList(),
    val artworkRef: String? = null,
    val durationMs: Long? = null,
    val downloaded: Boolean = false,
)

data class SongQuery(
    val text: String = "",
    val sort: SongSort = SongSort.TITLE,
    val ascending: Boolean = true,
    val sourceId: SourceId? = null,
    val downloadedOnly: Boolean = false,
)

data class LibraryHome(
    val songCount: Int = 0,
    val albumCount: Int = 0,
    val artistCount: Int = 0,
    val recentAdded: List<TrackSummary> = emptyList(),
    val recentPlayed: List<TrackSummary> = emptyList(),
) {
    init {
        require(songCount >= 0)
        require(albumCount >= 0)
        require(artistCount >= 0)
    }
}

data class SearchResults(
    val songs: List<TrackSummary> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
)

data class PlayableTrack(val track: Track, val locations: List<TrackLocation>)

sealed interface LibraryMutation {
    data class Upsert(
        override val sourceId: SourceId,
        val tracks: List<Track>,
        val locations: List<TrackLocation>,
    ) : LibraryMutation

    data class RetainSourceKeys(
        override val sourceId: SourceId,
        val retainedSourceKeys: Set<String>,
    ) : LibraryMutation

    val sourceId: SourceId
}

sealed interface SourceMutation {
    data class Upsert(val source: MusicSource) : SourceMutation
    data class Remove(val sourceId: SourceId) : SourceMutation
}

enum class MutationDisposition { APPLIED, SOURCE_RETIRED }

data class MutationResult(
    val inserted: Int = 0,
    val updated: Int = 0,
    val removed: Int = 0,
    val disposition: MutationDisposition = MutationDisposition.APPLIED,
) {
    init {
        require(inserted >= 0)
        require(updated >= 0)
        require(removed >= 0)
    }
}
