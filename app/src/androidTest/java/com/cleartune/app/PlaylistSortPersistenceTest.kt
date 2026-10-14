package com.cleartune.app

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.cleartune.core.database.*
import com.cleartune.core.datastore.AppPreferences
import com.cleartune.core.model.accountStorageKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PlaylistSortPersistenceTest {
    @Test fun sortSurvivesNewPreferencesInstanceAndIsScopedToAccountAndPlaylist() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val account = UUID.randomUUID().toString()
        val preferences = AppPreferences(context)
        preferences.setPlaylistSort(account, "p1", "ARTIST:true")
        preferences.setPlaylistSort(account, "p2", "ADDED:true")
        val reopened = AppPreferences(context)
        preferences.setFavoritesSort(account, "ADDED:false")
        assertEquals("ADDED:false", reopened.favoritesSort(account).first())
        assertEquals("DEFAULT:false", reopened.favoritesSort("other-$account").first())
        assertEquals("ARTIST:true", reopened.playlistSort(account, "p1").first())
        assertEquals("ADDED:true", reopened.playlistSort(account, "p2").first())
        assertEquals("DEFAULT:false", reopened.playlistSort("other-$account", "p1").first())
    }

    @Test fun additionHistorySurvivesRefreshReorderAndDatabaseReopenAndRemovedSongsLoseHistory() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = accountStorageKey("https://example.com", UUID.randomUUID().toString())
        val name = "cleartune_account_$key.db"
        var database = DatabaseFactory.create(context, key)
        try {
            database.mediaDao().recordPlaylistAdditions(listOf(PlaylistAdditionEntity("p", "song", 1234)))
            database.mediaDao().replacePlaylistSongs("p", listOf(PlaylistSongEntity("p", "song", 9)))
            database.close()
            database = DatabaseFactory.create(context, key)
            assertEquals(1234L, database.mediaDao().observePlaylistAdditions("p").first().single().addedAt)
            assertTrue(database.mediaDao().observePlaylistAdditions("another").first().isEmpty())
            database.mediaDao().replacePlaylistSongs("p", emptyList())
            assertTrue(database.mediaDao().observePlaylistAdditions("p").first().isEmpty())
            database.mediaDao().recordPlaylistAdditions(listOf(PlaylistAdditionEntity("p", "song", 9999)))
            assertEquals(9999L, database.mediaDao().observePlaylistAdditions("p").first().single().addedAt)
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test fun versionFourDatabaseMigratesWithoutInventingHistoricalAdditionTimes() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = accountStorageKey("https://example.com", UUID.randomUUID().toString())
        val name = "cleartune_account_$key.db"
        var database = DatabaseFactory.create(context, key)
        try {
            database.mediaDao().replacePlaylistSongs("p", listOf(PlaylistSongEntity("p", "old-song", 0)))
            database.close()
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("CREATE TABLE old_playlist_songs (playlistId TEXT NOT NULL, songId TEXT NOT NULL, position INTEGER NOT NULL, PRIMARY KEY(playlistId, songId))")
                it.execSQL("INSERT INTO old_playlist_songs SELECT playlistId, songId, position FROM playlist_songs")
                it.execSQL("DROP TABLE playlist_songs")
                it.execSQL("ALTER TABLE old_playlist_songs RENAME TO playlist_songs")
                it.execSQL("CREATE INDEX index_playlist_songs_playlistId ON playlist_songs(playlistId)")
                it.execSQL("CREATE INDEX index_playlist_songs_position ON playlist_songs(position)")
                it.execSQL("DROP TABLE playlist_additions")
                it.version = 4
            }
            database = DatabaseFactory.create(context, key)
            assertTrue(database.mediaDao().observePlaylistAdditions("p").first().isEmpty())
            database.mediaDao().recordPlaylistAdditions(listOf(PlaylistAdditionEntity("p", "new-song", 42)))
            assertEquals(42L, database.mediaDao().observePlaylistAdditions("p").first().single().addedAt)
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test fun versionFiveMigrationIgnoresLocalTimesAndRetainsSongOrder() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = accountStorageKey("https://example.com", UUID.randomUUID().toString())
        val name = "cleartune_account_$key.db"
        var database = DatabaseFactory.create(context, key)
        try {
            database.mediaDao().upsertSongs(listOf(com.cleartune.core.model.Song("s", "Song").toEntity()))
            database.mediaDao().recordPlaylistAdditions(listOf(PlaylistAdditionEntity("p", "s", 42)))
            database.close()
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("DROP TABLE playlist_songs")
                it.execSQL("CREATE TABLE playlist_songs (playlistId TEXT NOT NULL, songId TEXT NOT NULL, position INTEGER NOT NULL, PRIMARY KEY(playlistId, songId))")
                it.execSQL("CREATE INDEX index_playlist_songs_playlistId ON playlist_songs(playlistId)")
                it.execSQL("CREATE INDEX index_playlist_songs_position ON playlist_songs(position)")
                it.execSQL("INSERT INTO playlist_songs VALUES ('p', 's', 0)")
                it.version = 5
            }
            database = DatabaseFactory.create(context, key)
            val entries = database.mediaDao().observePlaylistEntries("p").first()
            assertEquals("s", entries.single().song.id)
            assertNull(entries.single().entryAddedAt)
            assertEquals(0, entries.single().entryPosition)
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test fun serverTimesSurviveReopenAndLegacyHistoryNeverBecomesMembershipTime() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = accountStorageKey("https://example.com", UUID.randomUUID().toString())
        val name = "cleartune_account_$key.db"
        var database = DatabaseFactory.create(context, key)
        try {
            var dao = database.mediaDao()
            dao.upsertSongs(listOf(com.cleartune.core.model.Song("s", "Song").toEntity()))
            dao.recordPlaylistAdditions(listOf(PlaylistAdditionEntity("p", "s", 999)))
            dao.replacePlaylistSongs("p", listOf(
                PlaylistSongEntity("p", "s", 0, 10, false),
                PlaylistSongEntity("p", "s", 1, 20, false),
                PlaylistSongEntity("p", "s", 2, null, false),
            ))
            assertEquals(listOf(10L, 20L, null), dao.observePlaylistEntries("p").first().map { it.entryAddedAt })
            database.close()
            database = DatabaseFactory.create(context, key)
            dao = database.mediaDao()
            assertEquals(listOf(10L, 20L, null), dao.observePlaylistEntries("p").first().map { it.entryAddedAt })
            // Original server: ignore local history even when the old flag is enabled.
            dao.replacePlaylistSongs("p", listOf(PlaylistSongEntity("p", "s", 0, allowLocalTime = true)))
            assertNull(dao.observePlaylistEntries("p").first().single().entryAddedAt)
            // Smart/custom unknown must suppress retained local estimates.
            dao.replacePlaylistSongs("p", listOf(PlaylistSongEntity("p", "s", 0, null, false)))
            assertNull(dao.observePlaylistEntries("p").first().single().entryAddedAt)
            dao.replacePlaylistSongs("p", listOf(PlaylistSongEntity("p", "s", 0), PlaylistSongEntity("p", "s", 1)))
            assertTrue(dao.observePlaylistEntries("p").first().all { it.entryAddedAt == null })
            dao.replacePlaylistSongs("p", emptyList())
            dao.replacePlaylistSongs("p", listOf(PlaylistSongEntity("p", "s", 0)))
            assertNull(dao.observePlaylistEntries("p").first().single().entryAddedAt)
        } finally { database.close(); context.deleteDatabase(name) }
    }
}
