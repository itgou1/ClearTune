package com.cleartune.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClearTuneMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ClearTuneDatabase::class.java,
    )

    @Test
    fun migration_1_2_backfills_download_source_and_preserves_existing_rows() {
        helper.createDatabase(DATABASE_NAME, 1).apply {
            execSQL(
                "INSERT INTO music_sources " +
                    "(id,name,type,baseUrl,allowCleartext,credentialAlias,enabled,lastSyncedAtEpochMs) " +
                    "VALUES ('remote','Remote','WEBDAV','https://example.test/dav/',0,NULL,1,NULL)",
            )
            execSQL("INSERT INTO tracks (id,title,durationMs,albumId,artworkRef,addedAtEpochMs) VALUES ('track','Song',1,NULL,NULL,1)")
            execSQL(
                "INSERT INTO track_locations " +
                    "(id,trackId,sourceId,sourceKey,type,uri,available,sizeBytes,etag,relativeFolder,displayName,modifiedEpochSeconds) " +
                    "VALUES ('location','track','remote','song.mp3','REMOTE_URL','https://example.test/dav/song.mp3',1,1,NULL,'','song.mp3',1)",
            )
            execSQL(
                "INSERT INTO downloads " +
                    "(id,trackId,state,bytesDownloaded,totalBytes,etag,partialPath,finalPath,errorMessage,updatedAtEpochMs) " +
                    "VALUES ('download','track','QUEUED',0,1,NULL,NULL,NULL,NULL,1)",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            2,
            true,
            ClearTuneDatabase.MIGRATION_1_2,
        ).use { migrated ->
            migrated.query(
                "SELECT sourceId, workGeneration, cleanupPending FROM downloads WHERE id = 'download'",
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals("remote", cursor.getString(0))
                assertEquals(0, cursor.getLong(1))
                assertEquals(0, cursor.getInt(2))
            }
            migrated.query("SELECT removed FROM music_sources WHERE id = 'remote'").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    private companion object {
        const val DATABASE_NAME = "migration-test"
    }
}
