package com.cleartune.app.player

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.cleartune.app.auth.AccountSession
import com.cleartune.app.library.MusicRepository
import com.cleartune.core.database.DatabaseFactory
import com.cleartune.core.database.toEntity
import com.cleartune.core.datastore.AppPreferences
import com.cleartune.core.model.*
import com.cleartune.core.network.*
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ListeningAndMissingSongValidationTest {
    @Test fun radioDoesNotReplaceSavedMusicQueueOrSendStationIdsToServerQueue() = runBlocking {
        Fixture().use { f ->
            val song = Song("live", "月光")
            f.db.mediaDao().upsertSongs(listOf(song.toEntity()))
            f.player.persistQueue(com.cleartune.core.player.PlayerUiState(
                queue = listOf(song), currentSong = song, currentIndex = 0, positionMs = 12000,
            ))
            val station = InternetRadioStation("cnr", "音乐之声", "https://example.com/live").asSong()
            val state = com.cleartune.core.player.PlayerUiState(
                queue = listOf(station), currentSong = station, currentIndex = 0,
            )
            f.player.persistQueue(state)
            f.player.saveServerQueue(state)
            val queue = f.db.queueDao().queue().single()
            assertEquals("live", queue.songId)
            assertEquals(12000L, queue.playbackPositionMs)
            assertTrue(f.requests.isEmpty())
        }
    }

    @Test fun searchRemovesConfirmedMissingButKeepsOtherVersionsAndTransportErrors() = runBlocking {
        Fixture().use { f ->
            val local = listOf("missing", "live", "broken", "new").map { Song(it, "月光") }
            f.db.mediaDao().upsertSongs(local.map { it.toEntity() })
            val result = f.music.search("月光") as RemoteResult.Success
            val removed = mutableListOf<String>()
            f.music.reconcileSearchSongs(local, result.value.songs) { removed += it }
            assertEquals(listOf("missing"), removed)
            assertNull(f.db.mediaDao().song("missing"))
            assertNotNull(f.db.mediaDao().song("live"))
            assertNotNull(f.db.mediaDao().song("broken"))
            assertFalse(f.music.localSearch("月光").songs.any { it.id == "missing" })
        }
    }

    @Test fun failedPlaybackOnlyRemovesConfirmedMissingSong() = runBlocking {
        Fixture().use { f ->
            f.db.mediaDao().upsertSongs(listOf("missing", "live", "broken").map { Song(it, "月光").toEntity() })
            assertFalse(f.player.removeMissingFailedSong("broken"))
            assertFalse(f.player.removeMissingFailedSong("live"))
            assertTrue(f.player.removeMissingFailedSong("missing"))
            assertNotNull(f.db.mediaDao().song("live"))
            assertNotNull(f.db.mediaDao().song("broken"))
            assertNull(f.db.mediaDao().song("missing"))
        }
    }

    @Test fun failedScrobbleUpdatesLocalTimeAndRetriesOriginalTimestamp() = runBlocking {
        Fixture().use { f ->
            f.db.mediaDao().upsertSongs(listOf(Song("live", "月光", lastPlayedAt = 100).toEntity()))
            f.failScrobble = true
            f.player.submitScrobble("live")
            val pending = f.db.activityDao().pendingCompletedPlays().single()
            assertTrue(pending.occurredAt > 100)
            assertEquals(pending.occurredAt, f.db.mediaDao().song("live")!!.lastPlayedAt)
            // Model a metadata refresh returning an older server timestamp.
            f.db.mediaDao().upsertSongs(listOf(Song("live", "月光", lastPlayedAt = 100).toEntity()))
            assertEquals(pending.occurredAt, f.db.mediaDao().song("live")!!.lastPlayedAt)
            f.failScrobble = false
            f.player.syncPendingScrobbles()
            assertTrue(f.db.activityDao().pendingCompletedPlays().isEmpty())
            assertEquals(listOf(pending.occurredAt.toString(), pending.occurredAt.toString()),
                f.requests.filter { it.path!!.contains("scrobble") }.map { it.getQueryParameter("time") })
            assertTrue(f.requests.filter { it.path!!.contains("scrobble") }.all { it.getQueryParameter("submission") == "true" })
        }
    }

    private class Fixture : AutoCloseable {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val server = ServerSocket(0)
        private val url = "http://127.0.0.1:${server.localPort}/"
        private val user = "validation-${UUID.randomUUID()}"
        private val key = accountStorageKey(url, user)
        val db = DatabaseFactory.create(context, key)
        private val session = AccountSession(ServerCredentials(url, user, "test", true),
            ServerProfile(url, user, "test", "1", "1.16.1", true), "test", db)
        val music = MusicRepository(session, OpenSubsonicApiFactory())
        val player = PlaybackRepository(context, session, OpenSubsonicApiFactory(), AppPreferences(context))
        val requests = CopyOnWriteArrayList<Uri>()
        @Volatile var failScrobble = false
        private val worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                socket.use {
                    it.soTimeout = 5_000
                    val input = it.getInputStream().bufferedReader()
                    val request = input.readLine() ?: return@use
                    while (!input.readLine().isNullOrEmpty()) Unit
                    val uri = Uri.parse("http://localhost" + request.split(' ')[1])
                    requests += uri
                    val id = uri.getQueryParameter("id")
                    val isScrobble = uri.path!!.contains("scrobble")
                    val status = if (id == "broken") 404 else if (isScrobble && failScrobble) 503 else 200
                    val payload = when {
                        isScrobble -> """{"status":"ok","version":"1.16.1"}"""
                        uri.path!!.contains("search3") -> """{"status":"ok","version":"1.16.1","searchResult3":{"song":[{"id":"new","title":"月光"}]}}"""
                        id == "missing" -> """{"status":"failed","version":"1.16.1","error":{"code":70,"message":"Not found"}}"""
                        else -> """{"status":"ok","version":"1.16.1","song":{"id":"$id","title":"月光"}}"""
                    }
                    val body = """{"subsonic-response":$payload}""".toByteArray()
                    it.getOutputStream().apply {
                        write("HTTP/1.1 $status Response\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body); flush()
                    }
                }
            }
        }
        override fun close() {
            session.revoke(); server.close(); worker.join(5_000); db.close()
            context.deleteDatabase("cleartune_account_$key.db")
        }
    }
}
