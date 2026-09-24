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
                it.execSQL("DROP TABLE playlist_additions")
                it.version = 4
            }
            database = DatabaseFactory.create(context, key)
            assertTrue(database.mediaDao().observePlaylistAdditions("p").first().isEmpty())
            database.mediaDao().recordPlaylistAdditions(listOf(PlaylistAdditionEntity("p", "new-song", 42)))
            assertEquals(42L, database.mediaDao().observePlaylistAdditions("p").first().single().addedAt)
        } finally { database.close(); context.deleteDatabase(name) }
    }
}
