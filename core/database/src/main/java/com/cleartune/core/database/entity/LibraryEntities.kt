package com.cleartune.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "music_sources")
data class MusicSourceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val baseUrl: String?,
    val allowCleartext: Boolean,
    val credentialAlias: String?,
    val enabled: Boolean,
    val lastSyncedAtEpochMs: Long?,
)

@Entity(tableName = "albums")
data class AlbumEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artworkRef: String?,
)

@Entity(tableName = "artists")
data class ArtistEntity(
    @PrimaryKey val id: String,
    val name: String,
)

@Entity(
    tableName = "tracks",
    indices = [Index("albumId"), Index("addedAtEpochMs")],
)
data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val durationMs: Long?,
    val albumId: String?,
    val artworkRef: String?,
    val addedAtEpochMs: Long,
)

@Entity(
    tableName = "track_locations",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MusicSourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("trackId"),
        Index("sourceId"),
        Index(value = ["sourceId", "sourceKey"], unique = true),
        Index("relativeFolder"),
    ],
)
data class TrackLocationEntity(
    @PrimaryKey val id: String,
    val trackId: String,
    val sourceId: String,
    val sourceKey: String,
    val type: String,
    val uri: String,
    val available: Boolean,
    val sizeBytes: Long?,
    val etag: String?,
    val relativeFolder: String,
    val displayName: String,
    val modifiedEpochSeconds: Long,
)

@Entity(
    tableName = "track_artists",
    primaryKeys = ["trackId", "artistId"],
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ArtistEntity::class,
            parentColumns = ["id"],
            childColumns = ["artistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("artistId")],
)
data class TrackArtistCrossRef(
    val trackId: String,
    val artistId: String,
)

@Fts4
@Entity(tableName = "track_search_fts")
data class TrackSearchFtsEntity(
    val trackId: String,
    val title: String,
    val albumTitle: String,
    val artistNames: String,
)

@Entity(tableName = "sync_sessions", indices = [Index("sourceId")])
data class SyncSessionEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val startedAtEpochMs: Long,
    val completedAtEpochMs: Long?,
    val phase: String,
    val processed: Int,
    val total: Int,
    val warningCount: Int,
    val errorMessage: String?,
)
