package com.cleartune.app.player

import android.content.Context
import android.net.ConnectivityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cleartune.app.auth.AccountSession
import com.cleartune.core.database.DatabaseFactory
import com.cleartune.core.database.toEntity
import com.cleartune.core.datastore.AppPreferences
import com.cleartune.core.model.ServerCredentials
import com.cleartune.core.model.ServerProfile
import com.cleartune.core.model.Song
import com.cleartune.core.model.accountStorageKey
import com.cleartune.core.network.OpenSubsonicApiFactory
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackMetadataRefreshTest {
    @Test fun successfulCheckUpdatesOnceAndThenUsesVerificationWindow() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.getSystemService(ConnectivityManager::class.java).activeNetwork != null)
        val server = SongMetadataServer()
        val baseUrl = "http://127.0.0.1:${server.port}"
        val username = "metadata-${UUID.randomUUID()}"
        val account = accountStorageKey(baseUrl, username)
        val database = DatabaseFactory.create(context, account)
        val credentials = ServerCredentials(baseUrl, username, "password", true)
        val session = AccountSession(credentials,
            ServerProfile(baseUrl, username, "test", "1", "1.16.1", true), "session", database)
        try {
            database.mediaDao().upsertSongs(listOf(Song("song-1", "本地旧标题", artistName = "歌手",
                albumName = "专辑", durationSeconds = 180).toEntity()))
            val repository = PlaybackRepository(context, session, OpenSubsonicApiFactory(), AppPreferences(context))

            assertEquals("服务器新标题", repository.refreshPlayedSong("song-1")?.title)
            assertEquals("服务器新标题", database.mediaDao().song("song-1")?.title)
            assertNull(repository.refreshPlayedSong("song-1"))
            assertEquals(1, server.requests.get())
        } finally {
            session.revoke()
            database.close()
            context.deleteDatabase("cleartune_account_$account.db")
            server.close()
        }
    }
}

private class SongMetadataServer : AutoCloseable {
    private val server = ServerSocket(0)
    val port: Int = server.localPort
    val requests = AtomicInteger()

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    runCatching { socket.use {
                        val input = it.getInputStream().bufferedReader()
                        input.readLine() ?: return@use
                        while (!input.readLine().isNullOrEmpty()) Unit
                        requests.incrementAndGet()
                        val body = """{"subsonic-response":{"status":"ok","version":"1.16.1","song":{"id":"song-1","title":"服务器新标题","artist":"歌手","album":"专辑","duration":180}}}""".toByteArray()
                        it.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(body)
                            flush()
                        }
                    } }
                }
            }
        }
    }

    override fun close() {
        server.close()
    }
}
