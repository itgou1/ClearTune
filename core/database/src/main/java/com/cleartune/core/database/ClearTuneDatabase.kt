package com.cleartune.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.cleartune.core.database.dao.LibraryReadDao
import com.cleartune.core.database.dao.LibraryWriteDao
import com.cleartune.core.database.dao.SourceDao
import com.cleartune.core.database.entity.AlbumEntity
import com.cleartune.core.database.entity.ArtistEntity
import com.cleartune.core.database.entity.MusicSourceEntity
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
    ],
    version = 1,
    exportSchema = true,
)
abstract class ClearTuneDatabase : RoomDatabase() {
    abstract fun libraryReadDao(): LibraryReadDao
    abstract fun libraryWriteDao(): LibraryWriteDao
    abstract fun sourceDao(): SourceDao

    companion object {
        fun build(context: Context, name: String = "cleartune.db"): ClearTuneDatabase = Room.databaseBuilder(
            context.applicationContext,
            ClearTuneDatabase::class.java,
            name,
        ).build()
    }
}
