package com.cleartune.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ArtistEntity::class,
        AlbumEntity::class,
        SongEntity::class,
        PlaylistEntity::class,
        PlaylistSongEntity::class,
        QueueItemEntity::class,
        DownloadEntity::class,
        PlayEventEntity::class,
        PendingMutationEntity::class,
        RecommendationSessionEntity::class,
        SearchDocumentEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class ClearTuneDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun queueDao(): QueueDao
    abstract fun downloadDao(): DownloadDao
    abstract fun activityDao(): ActivityDao
}
