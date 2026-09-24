package com.cleartune.app

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.cleartune.app.auth.AccountSession
import com.cleartune.app.library.MusicRepository
import com.cleartune.core.database.DatabaseFactory
import com.cleartune.core.database.toEntity
import com.cleartune.core.model.*
import com.cleartune.core.network.OpenSubsonicApiFactory
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class BatchPlaylistRepositoryTest {
    @Test fun openingPlaylistReusesRecentListRefreshAndStillLoadsMembers() = runBlocking {
        Fixture().use { fixture ->
            assertNull(fixture.repository.refreshPlaylists())
            assertNull(fixture.repository.openPlaylist("p"))
            assertEquals(1, fixture.requests.count { it.path?.contains("getPlaylists") == true })
            assertEquals(1, fixture.requests.count { it.path?.contains("getPlaylist.view") == true })
            assertEquals(listOf("existing"), fixture.repository.playlistSongs("p").first().map(Song::id))
        }
    }

    @Test fun playlistDetailWithoutArtworkPreservesListingArtworkAndUpdatesMetadata() = runBlocking {
        Fixture().use { fixture ->
            fixture.seedPlaylist()
            assertNull(fixture.repository.loadPlaylist("p"))
            val playlist = fixture.repository.playlist("p").first()!!
            assertEquals("listing-cover", playlist.coverArtId)
            assertEquals("Target", playlist.name)
            assertEquals(1, playlist.songCount)
        }
    }

    @Test fun playlistDetailWithBlankArtworkPreservesExistingCover() = runBlocking {
        Fixture().use { fixture ->
            fixture.seedPlaylist()
            fixture.coverArt = "  "
            assertNull(fixture.repository.loadPlaylist("p"))
            assertEquals("listing-cover", fixture.repository.playlist("p").first()!!.coverArtId)
        }
    }

    @Test fun playlistDetailNewArtworkReplacesOldCover() = runBlocking {
        Fixture().use { fixture ->
            fixture.seedPlaylist()
            fixture.coverArt = "new-cover"
            assertNull(fixture.repository.loadPlaylist("p"))
            assertEquals("new-cover", fixture.repository.playlist("p").first()!!.coverArtId)
        }
    }

    @Test fun addsMultipleSongsOnceAndSkipsExistingAndDuplicateIds() = runBlocking {
        Fixture().use { fixture ->
            val result = fixture.repository.addPlaylistSongs("p", listOf("existing", "b", "c", "b"))
            assertNull(result.error)
            assertEquals(2, result.addedCount)
            assertEquals(1, result.skippedCount)
            assertEquals(listOf("b", "c"), fixture.mutations.single().getQueryParameters("songIdToAdd"))
        }
    }

    @Test fun allExistingDoesNotSendUpdate() = runBlocking {
        Fixture().use { fixture ->
            val result = fixture.repository.addPlaylistSongs("p", listOf("existing"))
            assertEquals(0, result.addedCount)
            assertEquals(1, result.skippedCount)
            assertTrue(fixture.mutations.isEmpty())
        }
    }

    @Test fun createPlaylistIncludesSelectedSongsInSingleRequest() = runBlocking {
        Fixture().use { fixture ->
            val result = fixture.repository.createPlaylistWithSongs(" New list ", listOf("b", "c", "b"))
            assertNull(result.error)
            assertEquals(2, result.addedCount)
            val request = fixture.mutations.single()
            assertEquals("New list", request.getQueryParameter("name"))
            assertEquals(listOf("b", "c"), request.getQueryParameters("songId"))
        }
    }

    @Test fun serverFailureCanRetryWithoutLosingRequestedSongs() = runBlocking {
        Fixture().use { fixture ->
            fixture.failWrite = true
            assertNotNull(fixture.repository.addPlaylistSongs("p", listOf("b", "c")).error)
            fixture.failWrite = false
            assertEquals(2, fixture.repository.addPlaylistSongs("p", listOf("b", "c")).addedCount)
            assertEquals(2, fixture.mutations.size)
        }
    }

    @Test fun acceptedWriteWithFailedRefreshIsNotReportedAsFailedAddition() = runBlocking {
        Fixture().use { fixture ->
            fixture.failRefresh = true
            val result = fixture.repository.addPlaylistSongs("p", listOf("b", "c"))
            assertNull(result.error)
            assertNotNull(result.refreshError)
            assertEquals(2, result.addedCount)
            assertEquals(1, fixture.mutations.size)
        }
    }

    private class Fixture : AutoCloseable {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val server = ServerSocket(0)
        private val baseUrl = "http://127.0.0.1:${server.localPort}/"
        private val username = "batch-${UUID.randomUUID()}"
        private val key = accountStorageKey(baseUrl, username)
        private val database = DatabaseFactory.create(context, key)
        val requests = CopyOnWriteArrayList<Uri>()
        val mutations = CopyOnWriteArrayList<Uri>()
        @Volatile var failWrite = false
        @Volatile var failRefresh = false
        @Volatile var coverArt: String? = null
        suspend fun seedPlaylist() {
            database.mediaDao().upsertPlaylists(listOf(Playlist("p", "Old name", songCount = 9,
                coverArtId = "listing-cover").toEntity()))
        }
        private val session = AccountSession(ServerCredentials(baseUrl, username, "test", true),
            ServerProfile(baseUrl, username, "test", "1", "1.16.1", true), "test", database)
        val repository = MusicRepository(session, OpenSubsonicApiFactory())
        private val worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                socket.use {
                    it.soTimeout = 5_000
                    val input = it.getInputStream().bufferedReader()
                    val request = input.readLine() ?: return@use
                    while (!input.readLine().isNullOrEmpty()) { /* Consume HTTP headers. */ }
                    val uri = Uri.parse("http://localhost" + request.split(" ")[1])
                    requests += uri
                    val write = uri.path?.contains("updatePlaylist") == true || uri.path?.contains("createPlaylist") == true
                    if (write) mutations += uri
                    val failed = write && failWrite || !write && failRefresh && mutations.isNotEmpty()
                    val payload = if (failed) {
                        """{"status":"failed","version":"1.16.1","error":{"code":50,"message":"Test failure"}}"""
                    } else when {
                        uri.path?.contains("getPlaylists") == true ->
                            """{"status":"ok","version":"1.16.1","playlists":{"playlist":[{"id":"p","name":"Target","songCount":1}]}}"""
                        uri.path?.contains("getPlaylist") == true ->
                            """{"status":"ok","version":"1.16.1","playlist":{"id":"p","name":"Target","songCount":1,${coverArt?.let { "\"coverArt\":\"$it\"," }.orEmpty()}"entry":[{"id":"existing","title":"Existing"}]}}"""
                        else -> """{"status":"ok","version":"1.16.1"}"""
                    }
                    val bytes = """{"subsonic-response":$payload}""".toByteArray()
                    it.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes)
                        flush()
                    }
                }
            }
        }

        override fun close() {
            session.revoke()
            server.close()
            worker.join(5_000)
            database.close()
            context.deleteDatabase("cleartune_account_$key.db")
        }
    }
}
