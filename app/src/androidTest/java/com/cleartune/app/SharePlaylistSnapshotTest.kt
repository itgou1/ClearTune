package com.cleartune.app

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.cleartune.app.auth.AccountSession
import com.cleartune.app.share.ShareKind
import com.cleartune.app.share.ShareRepository
import com.cleartune.app.share.ShareTarget
import com.cleartune.app.share.shareMessage
import com.cleartune.core.database.DatabaseFactory
import com.cleartune.core.model.ServerCredentials
import com.cleartune.core.model.ServerProfile
import com.cleartune.core.model.accountStorageKey
import com.cleartune.core.network.OpenSubsonicApiFactory
import com.cleartune.core.network.RemoteResult
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SharePlaylistSnapshotTest {
    @Test fun removedSongsUseCreationSnapshotAfterReload() = checkSnapshot(2)
    @Test fun oneRemainingSongKeepsPlaylistKindAndCorrectCount() = checkSnapshot(1)
    @Test fun addedSongsUseCreationSnapshotAfterReload() = checkSnapshot(5)

    private fun checkSnapshot(actualCount: Int) = runBlocking {
        Fixture(actualCount).use { fixture ->
            val created = fixture.repository().create(fixture.openedTarget, "Test note", 7)
                as RemoteResult.Success
            assertEquals((1..actualCount).map { "song-$it" }, fixture.sharedIds.single())
            assertEquals(actualCount, created.value.songCount)
            assertEquals(ShareKind.PLAYLIST, created.value.kind)
            assertTrue(shareMessage(created.value).contains("共 $actualCount 首歌曲"))
            // getShares omits entries: the correct count must survive in saved metadata.
            val reloaded = fixture.repository().list() as RemoteResult.Success
            assertEquals(actualCount, reloaded.value.single().songCount)
            assertEquals(shareMessage(created.value), shareMessage(reloaded.value.single()))
        }
    }

    @Test fun playlistEmptiedAfterOpeningDoesNotCreateShare() = runBlocking {
        Fixture(0).use { fixture ->
            assertTrue(fixture.repository().create(fixture.openedTarget, "", 7) is RemoteResult.Failure)
            assertTrue(fixture.sharedIds.isEmpty())
        }
    }

    @Test fun incompletePlaylistDoesNotCreatePartialShare() = runBlocking {
        Fixture(2, advertisedCount = 3).use { fixture ->
            assertTrue(fixture.repository().create(fixture.openedTarget, "", 7) is RemoteResult.Failure)
            assertTrue(fixture.sharedIds.isEmpty())
        }
    }

    private class Fixture(actualCount: Int, advertisedCount: Int = actualCount) : AutoCloseable {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val server = ServerSocket(0)
        private val baseUrl = "http://127.0.0.1:${server.localPort}/"
        private val username = "share-snapshot-${UUID.randomUUID()}"
        private val key = accountStorageKey(baseUrl, username)
        private val database = DatabaseFactory.create(context, key)
        private val session = AccountSession(
            ServerCredentials(baseUrl, username, "test-only", true),
            ServerProfile(baseUrl, username, "test", "1", "1.16.1", true), "test", database,
        )
        val openedTarget = ShareTarget(ShareKind.PLAYLIST, "playlist", "Test playlist", "3 首歌曲", songCount = 3)
        val sharedIds = CopyOnWriteArrayList<List<String>>()
        fun repository() = ShareRepository(session, OpenSubsonicApiFactory(), context)
        private val share = JSONObject().put("id", "snapshot")
            .put("url", "https://example.com/share/snapshot")
            .put("username", username).put("description", "Test note")
            .put("expires", "2099-01-01T00:00:00Z")
        private val worker = thread(isDaemon = true, name = "share-snapshot-fixture") {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                socket.use {
                    it.soTimeout = 5_000
                    val input = it.getInputStream().bufferedReader()
                    val request = input.readLine() ?: return@use
                    var length = 0
                    while (true) {
                        val header = input.readLine().orEmpty()
                        if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", ignoreCase = true)) {
                            length = header.substringAfter(':').trim().toInt()
                        }
                    }
                    val body = CharArray(length)
                    var offset = 0
                    while (offset < length) {
                        val read = input.read(body, offset, length - offset)
                        check(read > 0)
                        offset += read
                    }
                    val uri = Uri.parse("http://localhost" + request.split(' ')[1])
                    val response = JSONObject().put("status", "ok").put("version", "1.16.1")
                    when (uri.lastPathSegment) {
                        "getPlaylist.view" -> response.put("playlist", JSONObject()
                            .put("id", "playlist").put("name", "Test playlist")
                            .put("songCount", advertisedCount)
                            .put("entry", JSONArray().apply {
                                for (index in 1..actualCount) put(JSONObject()
                                    .put("id", "song-$index").put("title", "Song $index"))
                            }))
                        "createShare.view" -> {
                            sharedIds += Uri.parse("http://localhost/?${String(body)}").getQueryParameters("id")
                            response.put("shares", JSONObject().put("share", JSONArray().put(share)))
                        }
                        "getShares.view" -> response.put("shares", JSONObject().put("share", JSONArray().put(share)))
                    }
                    val bytes = JSONObject().put("subsonic-response", response).toString().toByteArray()
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
            context.getSharedPreferences("music_shares", 0).edit().remove("$key:snapshot").commit()
            database.close()
            context.deleteDatabase("cleartune_account_$key.db")
        }
    }
}
