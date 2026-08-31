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
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
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

    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS search_documents USING FTS4(
                    entityType TEXT NOT NULL,
                    entityId TEXT NOT NULL,
                    title TEXT NOT NULL,
                    subtitle TEXT NOT NULL,
                    keywords TEXT NOT NULL,
                    pinyin TEXT NOT NULL,
                    initials TEXT NOT NULL,
                    tokenize=unicode61
                )
                """.trimIndent(),
            )
        }
    }
}
