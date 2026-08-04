package com.cleartune.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cleartune.core.database.dao.DownloadDao
import com.cleartune.core.database.dao.LibraryReadDao
import com.cleartune.core.database.dao.LibraryWriteDao
import com.cleartune.core.database.dao.PlaybackDao
import com.cleartune.core.database.dao.PlaylistDao
import com.cleartune.core.database.dao.SettingsDao
import com.cleartune.core.database.dao.SourceDao
import com.cleartune.core.database.entity.AppSettingsEntity
import com.cleartune.core.database.entity.AlbumEntity
import com.cleartune.core.database.entity.ArtistEntity
import com.cleartune.core.database.entity.MusicSourceEntity
import com.cleartune.core.database.entity.DownloadEntity
import com.cleartune.core.database.entity.PlaybackHistoryEntity
import com.cleartune.core.database.entity.PlaybackQueueEntity
import com.cleartune.core.database.entity.PlaybackQueueItemEntity
import com.cleartune.core.database.entity.PlaybackStateEntity
import com.cleartune.core.database.entity.PlaylistEntity
import com.cleartune.core.database.entity.PlaylistTrackCrossRef
import com.cleartune.core.database.entity.SyncSessionEntity
import com.cleartune.core.database.entity.TrackArtistCrossRef
import com.cleartune.core.database.entity.TrackEntity
import com.cleartune.core.database.entity.TrackLocationEntity
import com.cleartune.core.database.entity.TrackSearchFtsEntity

@Database(
    entities = [
        MusicSourceEntity::class,
        AlbumEntity::class,
        ArtistEntity::class,
        TrackEntity::class,
        TrackLocationEntity::class,
        TrackArtistCrossRef::class,
        TrackSearchFtsEntity::class,
        SyncSessionEntity::class,
        PlaylistEntity::class,
        PlaylistTrackCrossRef::class,
        PlaybackHistoryEntity::class,
        DownloadEntity::class,
        PlaybackQueueEntity::class,
        PlaybackQueueItemEntity::class,
        PlaybackStateEntity::class,
        AppSettingsEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class ClearTuneDatabase : RoomDatabase() {
    abstract fun libraryReadDao(): LibraryReadDao
    abstract fun libraryWriteDao(): LibraryWriteDao
    abstract fun sourceDao(): SourceDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun downloadDao(): DownloadDao
    abstract fun playbackDao(): PlaybackDao
    abstract fun settingsDao(): SettingsDao

    companion object {
        fun build(context: Context, name: String = "cleartune.db"): ClearTuneDatabase = Room.databaseBuilder(
            context.applicationContext,
            ClearTuneDatabase::class.java,
            name,
        ).addCallback(LOCAL_SOURCE_CALLBACK).build()

        val LOCAL_SOURCE_CALLBACK = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "INSERT OR IGNORE INTO music_sources " +
                        "(id, name, type, baseUrl, allowCleartext, credentialAlias, enabled, lastSyncedAtEpochMs) " +
                        "VALUES ('local', '本地音乐', 'LOCAL', NULL, 0, NULL, 1, NULL)",
                )
            }
        }
    }
}
