package com.cleartune.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object DatabaseFactory {
    fun create(context: Context): ClearTuneDatabase {
        return Room.databaseBuilder(
            context.applicationContext,
            ClearTuneDatabase::class.java,
            "cleartune.db",
        ).addMigrations(MIGRATION_1_2).build()
    }

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE songs ADD COLUMN replayGainTrackDb REAL")
            db.execSQL("ALTER TABLE songs ADD COLUMN replayGainAlbumDb REAL")
            db.execSQL("ALTER TABLE songs ADD COLUMN replayGainTrackPeak REAL")
            db.execSQL("ALTER TABLE songs ADD COLUMN replayGainAlbumPeak REAL")
            db.execSQL("ALTER TABLE songs ADD COLUMN replayGainBaseDb REAL")
            db.execSQL("ALTER TABLE songs ADD COLUMN replayGainFallbackDb REAL")
        }
    }
}
