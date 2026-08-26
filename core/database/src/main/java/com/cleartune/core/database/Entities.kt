package com.cleartune.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "artists")
data class ArtistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val albumCount: Int,
    val coverArtId: String?,
    val starredAt: Long?,
    val updatedAt: Long,
)

@Entity(
    tableName = "albums",
    indices = [Index("artistId"), Index("createdAt")],
)
data class AlbumEntity(
    @PrimaryKey val id: String,
    val name: String,
    val artistId: String?,
    val artistName: String,
    val year: Int?,
    val songCount: Int,
    val durationSeconds: Long,
    val coverArtId: String?,
    val starredAt: Long?,
    val createdAt: Long?,
    val updatedAt: Long,
)

@Entity(
    tableName = "songs",
    indices = [Index("albumId"), Index("artistId"), Index("genre"), Index("lastPlayedAt")],
)
data class SongEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artistId: String?,
    val artistName: String,
    val albumId: String?,
    val albumName: String,
    val durationSeconds: Long,
    val trackNumber: Int?,
    val discNumber: Int?,
    val year: Int?,
    val genre: String?,
    val coverArtId: String?,
    val contentType: String?,
    val suffix: String?,
    val bitRate: Int?,
    val sizeBytes: Long?,
    val playCount: Long,
    val lastPlayedAt: Long?,
    val starredAt: Long?,
    val createdAt: Long?,
    val replayGainTrackDb: Double?,
    val replayGainAlbumDb: Double?,
    val replayGainTrackPeak: Double?,
    val replayGainAlbumPeak: Double?,
    val replayGainBaseDb: Double?,
    val replayGainFallbackDb: Double?,
    val updatedAt: Long,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val songCount: Int,
    val durationSeconds: Long,
    val owner: String?,
    val isPublic: Boolean,
    val coverArtId: String?,
    val changedAt: Long?,
    val updatedAt: Long,
)

@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "songId"],
    indices = [Index("playlistId"), Index("position")],
)
data class PlaylistSongEntity(
    val playlistId: String,
    val songId: String,
    val position: Int,
)

@Entity(
    tableName = "queue_items",
    primaryKeys = ["queueId", "songId"],
    indices = [Index("position")],
)
data class QueueItemEntity(
    val queueId: String = "current",
    val songId: String,
    val position: Int,
    val isCurrent: Boolean = false,
    val playbackPositionMs: Long = 0,
    val updatedAt: Long,
)

@Entity(tableName = "downloads", indices = [Index("songId", unique = true)])
data class DownloadEntity(
    @PrimaryKey val requestId: String,
    val songId: String,
    val state: String,
    val bytesDownloaded: Long,
    val totalBytes: Long?,
    val localUri: String?,
    val failureReason: String?,
    val updatedAt: Long,
)

@Entity(tableName = "play_events", indices = [Index("songId"), Index("occurredAt")])
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: String,
    val type: String,
    val occurredAt: Long,
    val synced: Boolean = false,
)

@Entity(tableName = "pending_mutations", indices = [Index("createdAt")])
data class PendingMutationEntity(
    @PrimaryKey val id: String,
    val type: String,
    val targetId: String,
    val payload: String?,
    val attemptCount: Int = 0,
    val createdAt: Long,
)

@Entity(tableName = "recommendation_sessions")
data class RecommendationSessionEntity(
    @PrimaryKey val id: String,
    val strategy: String,
    val seed: Long,
    val songIds: String,
    val createdAt: Long,
)
