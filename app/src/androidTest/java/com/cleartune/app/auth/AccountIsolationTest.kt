package com.cleartune.app.auth

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cleartune.core.database.DatabaseFactory
import com.cleartune.core.database.DownloadEntity
import com.cleartune.core.database.PendingMutationEntity
import com.cleartune.core.database.toEntity
import com.cleartune.core.model.ServerCredentials
import com.cleartune.core.model.ServerProfile
import com.cleartune.core.model.Song
import com.cleartune.core.model.accountStorageKey
import com.cleartune.app.library.MusicRepository
import com.cleartune.core.network.OpenSubsonicApiFactory
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import com.cleartune.core.datastore.AppPreferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountIsolationTest {
    @Test fun searchHistoryAndSyncTimeBelongToTheirAccount() = runBlocking {
        val preferences = AppPreferences(InstrumentationRegistry.getInstrumentation().targetContext)
        val server = "https://${UUID.randomUUID()}.example/"
        val alice = accountStorageKey(server, "alice")
        val bob = accountStorageKey(server, "bob")
        preferences.addRecentSearch(alice, "Alice private search")
        preferences.setLastLibrarySyncEpochMs(alice, 123L)
        assertEquals(listOf("Alice private search"), preferences.accountLibrarySettings(alice).first().recentSearches)
        assertTrue(preferences.accountLibrarySettings(bob).first().recentSearches.isEmpty())
        assertEquals(0L, preferences.accountLibrarySettings(bob).first().lastLibrarySyncEpochMs)
        preferences.clearRecentSearches(bob)
        assertEquals(1, preferences.accountLibrarySettings(alice).first().recentSearches.size)
        preferences.clearRecentSearches(alice)
        preferences.setLastLibrarySyncEpochMs(alice, 0L)
    }

    @Test fun sameSongIdsDoNotShareLibraryDownloadsOrPendingMutations() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val server = "https://${UUID.randomUUID()}.example/"
        val keys = listOf("alice", "bob").map { accountStorageKey(server, it) }
        val alice = DatabaseFactory.create(context, keys[0])
        val bob = DatabaseFactory.create(context, keys[1])
        try {
            alice.mediaDao().upsertSongs(listOf(Song("same-id", "Alice private song").toEntity()))
            bob.mediaDao().upsertSongs(listOf(Song("same-id", "Bob song").toEntity()))
            alice.downloadDao().upsert(DownloadEntity("download-a", "same-id", "COMPLETED", 10, 10,
                "file:///private-alice/song.mp3", null, 1))
            alice.activityDao().upsertMutation(PendingMutationEntity("favorite-a", "STAR_SONG", "same-id", null, createdAt = 1))

            assertEquals("Bob song", bob.mediaDao().song("same-id")?.title)
            assertNull(bob.downloadDao().forSong("same-id"))
            assertTrue(bob.activityDao().pendingMutations().isEmpty())
            assertEquals("favorite-a", alice.activityDao().pendingMutations().single().id)

            val oldSession = AccountSession(ServerCredentials(server, "alice", "alice-secret"),
                ServerProfile(server, "alice", "test", "1", "1.16.1", true), "old-login", alice)
            val oldRepository = MusicRepository(oldSession, OpenSubsonicApiFactory())
            assertTrue(oldRepository.coverArtUrl("cover")!!.contains("u=alice"))
            oldSession.revoke()
            val newSession = AccountSession(ServerCredentials(server, "bob", "bob-secret"),
                ServerProfile(server, "bob", "test", "1", "1.16.1", true), "new-login", bob)
            assertNull(oldSession.credentials())
            assertNull(oldRepository.coverArtUrl("cover"))
            assertTrue(MusicRepository(newSession, OpenSubsonicApiFactory()).coverArtUrl("cover")!!.contains("u=bob"))
            assertEquals("bob", newSession.credentials()?.username)
            assertEquals("Alice private song", oldSession.database.mediaDao().song("same-id")?.title)

            alice.close()
            val reopened = DatabaseFactory.create(context, keys[0])
            try {
                assertEquals("Alice private song", reopened.mediaDao().song("same-id")?.title)
                assertEquals("download-a", reopened.downloadDao().forSong("same-id")?.requestId)
            } finally {
                reopened.close()
            }
        } finally {
            alice.close()
            bob.close()
            keys.forEach { context.deleteDatabase("cleartune_account_$it.db") }
        }
    }

    @Test fun logoutPausesOnlyUnfinishedDownloads() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = accountStorageKey("https://${UUID.randomUUID()}.example/", "test")
        val database = DatabaseFactory.create(context, key)
        try {
            listOf("QUEUED", "DOWNLOADING", "COMPLETED").forEach { state ->
                database.downloadDao().upsert(DownloadEntity(state, state, state, 10, 10, null, null, 1))
            }
            database.downloadDao().pauseActiveDownloads()
            assertEquals("PAUSED", database.downloadDao().forSong("QUEUED")?.state)
            assertEquals("PAUSED", database.downloadDao().forSong("DOWNLOADING")?.state)
            assertEquals("COMPLETED", database.downloadDao().forSong("COMPLETED")?.state)
        } finally {
            database.close()
            context.deleteDatabase("cleartune_account_$key.db")
        }
    }
}
