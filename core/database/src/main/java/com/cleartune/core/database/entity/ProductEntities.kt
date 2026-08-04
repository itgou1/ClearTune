package com.cleartune.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "playlists", indices = [Index("createdAtEpochMs")])
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "playlist_tracks",
    foreignKeys = [
        ForeignKey(PlaylistEntity::class, ["id"], ["playlistId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(TrackEntity::class, ["id"], ["trackId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("playlistId"), Index("trackId"), Index(value = ["playlistId", "position"], unique = true)],
)
data class PlaylistTrackCrossRef(
    @PrimaryKey val id: String,
    val playlistId: String,
    val trackId: String,
    val position: Int,
    val addedAtEpochMs: Long,
)

@Entity(
    tableName = "playback_history",
    foreignKeys = [ForeignKey(TrackEntity::class, ["id"], ["trackId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("trackId"), Index("playedAtEpochMs")],
)
data class PlaybackHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String,
    val playedAtEpochMs: Long,
)

@Entity(
    tableName = "downloads",
    foreignKeys = [ForeignKey(TrackEntity::class, ["id"], ["trackId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("trackId", unique = true), Index("state")],
)
data class DownloadEntity(
    @PrimaryKey val id: String,
    val trackId: String,
    val state: String,
    val bytesDownloaded: Long,
    val totalBytes: Long?,
    val finalPath: String?,
    val errorMessage: String?,
    val updatedAtEpochMs: Long,
)

@Entity(tableName = "playback_queues")
data class PlaybackQueueEntity(
    @PrimaryKey val id: String,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "playback_queue_items",
    foreignKeys = [
        ForeignKey(PlaybackQueueEntity::class, ["id"], ["queueId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(TrackEntity::class, ["id"], ["trackId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("queueId"), Index("trackId"), Index(value = ["queueId", "position"], unique = true)],
)
data class PlaybackQueueItemEntity(
    @PrimaryKey val id: String,
    val queueId: String,
    val trackId: String,
    val position: Int,
)

@Entity(
    tableName = "playback_state",
    foreignKeys = [ForeignKey(PlaybackQueueEntity::class, ["id"], ["queueId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("queueId")],
)
data class PlaybackStateEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val queueId: String,
    val currentIndex: Int,
    val positionMs: Long,
    val playWhenReady: Boolean,
    val repeatMode: String,
    val shuffleEnabled: Boolean,
) {
    companion object { const val SINGLETON_ID = 0 }
}

@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val themeMode: String,
    val reducedMotionMode: String,
) {
    companion object { const val SINGLETON_ID = 0 }
}
