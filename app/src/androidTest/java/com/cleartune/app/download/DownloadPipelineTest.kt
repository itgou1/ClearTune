package com.cleartune.app.download

import android.media.MediaPlayer
import android.graphics.Bitmap
import com.cleartune.app.artwork.ArtworkCache
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import com.cleartune.app.auth.AccountSession
import com.cleartune.app.player.PlaybackRepository
import com.cleartune.core.database.DatabaseFactory
import com.cleartune.core.database.DownloadEntity
import com.cleartune.core.database.toEntity
import com.cleartune.core.datastore.AppPreferences
import com.cleartune.core.datastore.CredentialsStore
import com.cleartune.core.model.*
import com.cleartune.core.network.OpenSubsonicApiFactory
import java.io.File
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadPipelineTest {
    @Test fun actualDownloadPublishesProgressAndPlaysOffline() = runBlocking {
        fixture { f ->
            val seen = CopyOnWriteArrayList<DownloadItem>()
            val observation = f.scope.launch { f.repository.downloads.collect { seen.addAll(it) } }
            assertEquals(1, f.repository.enqueue(listOf(f.song)).queuedCount)
            val done = f.awaitState("COMPLETED")
            assertTrue(seen.any { it.state == DownloadState.DOWNLOADING && it.bytesDownloaded > 0 })
            val file = File(Uri.parse(done.localUri).path!!)
            assertArrayEquals(f.server.audio, file.readBytes())
            val artwork = ArtworkCache.localFile(f.context, f.session.accountKey, "test-cover")!!
            assertArrayEquals(f.server.cover, artwork.readBytes())
            assertTrue(ArtworkCache.clearTemporary(f.context))
            assertTrue(artwork.exists())
            f.server.close()
            val urls = PlaybackRepository(f.context, f.session, OpenSubsonicApiFactory(), f.preferences)
                .urls(listOf(f.song))!!
            assertEquals(done.localUri, urls.streams[f.song.id])
            assertEquals(Uri.fromFile(artwork).toString(), urls.artwork[f.song.id])
            val player = MediaPlayer()
            try {
                player.setDataSource(f.context, Uri.parse(urls.streams[f.song.id]))
                player.setVolume(0f, 0f)
                player.prepare()
                assertTrue(player.duration > 0)
                player.start()
                assertTrue(player.isPlaying)
            } finally { player.release() }
            observation.cancelAndJoin()
        }
    }

    @Test fun forbiddenDownloadFailsOnceWithReason() = runBlocking {
        fixture(code = 403) { f ->
            f.repository.enqueue(listOf(f.song))
            val row = f.awaitState("FAILED")
            assertTrue(row.failureReason!!.contains("权限"))
            delay(500)
            assertEquals(1, f.server.requests.size)
            assertNull(row.localUri)
        }
    }

    @Test fun missingCoverDoesNotFailAudioAndExistingDownloadIsBackfilled() = runBlocking {
        fixture { f ->
            f.server.coverResponseCode = 404
            f.repository.enqueue(listOf(f.song))
            val completed = f.awaitState("COMPLETED")
            assertEquals(OfflineArtworkWorker.ARTWORK_WARNING, completed.failureReason)
            assertNull(ArtworkCache.localFile(f.context, f.session.accountKey, "test-cover"))
            f.server.coverResponseCode = 200
            OfflineArtworkWorker.enqueue(f.context, f.session.accountKey, f.session.token, false, replace = true)
            withTimeout(30_000) {
                while (ArtworkCache.localFile(f.context, f.session.accountKey, "test-cover") == null ||
                    f.dao.forSong(f.song.id)?.failureReason != null) delay(50)
            }
            assertEquals(1, f.server.requests.size)
            assertEquals(completed.localUri, f.dao.forSong(f.song.id)?.localUri)
        }
    }

    @Test fun invalidImageAndCancelledSessionNeverPublishOfflineArtwork() = runBlocking {
        fixture { f ->
            f.server.coverPayload = "<html>not an image</html>".toByteArray()
            try {
                ArtworkCache.saveOffline(f.context, f.credentials, "invalid") { }
                fail("Invalid image was accepted")
            } catch (_: java.io.IOException) { }
            assertNull(ArtworkCache.localFile(f.context, f.session.accountKey, "invalid"))
            f.server.coverPayload = f.server.cover
            var checks = 0
            try {
                ArtworkCache.saveOffline(f.context, f.credentials, "cancelled") {
                    if (++checks >= 2) throw CancellationException("Account revoked")
                }
                fail("Cancelled request was published")
            } catch (_: CancellationException) { }
            assertNull(ArtworkCache.localFile(f.context, f.session.accountKey, "cancelled"))
            val folder = File(f.context.filesDir, "accounts/${f.session.accountKey}/offline_artwork")
            assertTrue(folder.listFiles().orEmpty().none { it.extension == "part" })
        }
    }

    @Test fun temporaryServerErrorAutomaticallyRetriesAndCompletes() = runBlocking {
        fixture(code = 503) { f ->
            f.repository.enqueue(listOf(f.song))
            withTimeout(15_000) {
                while (f.server.requests.isEmpty()) delay(50)
            }
            f.server.responseCode = 200
            val done = f.awaitState("COMPLETED")
            assertEquals(f.server.audio.size.toLong(), done.bytesDownloaded)
            assertEquals(2, f.server.requests.size)
        }
    }

    @Test fun reopeningReconcilesOrphanedTasksInsteadOfWaitingForever() = runBlocking {
        fixture { f ->
            f.dao.upsert(DownloadEntity(UUID.randomUUID().toString(), f.song.id, "QUEUED",
                0, null, null, null, System.currentTimeMillis() - 60_000))
            val failed = f.awaitState("FAILED")
            assertTrue(failed.failureReason!!.contains("后台任务不存在"))
            assertTrue(f.server.requests.isEmpty())
        }
    }

    @Test fun pauseResumeAndDeleteDoNotAllowStaleWrites() = runBlocking {
        fixture { f ->
            f.repository.enqueue(listOf(f.song))
            withTimeout(30_000) {
                while ((f.dao.forSong(f.song.id)?.bytesDownloaded ?: 0) < 32_000) delay(50)
            }
            val active = f.repository.downloads.first { it.isNotEmpty() }.single()
            f.repository.pause(active)
            delay(300)
            val paused = f.dao.forSong(f.song.id)!!
            assertEquals("PAUSED", paused.state)
            assertEquals(0, f.dao.updateProgress(paused.requestId, "COMPLETED", 999, 999,
                "file:///stale", null, System.currentTimeMillis()))
            f.repository.retry(active, f.song)
            val completed = f.awaitState("COMPLETED")
            assertNotEquals(paused.requestId, completed.requestId)
            assertArrayEquals(f.server.audio, File(Uri.parse(completed.localUri).path!!).readBytes())
            assertTrue("Continue should request a byte range", f.server.requests.any { it > 0 })
            f.repository.delete(f.repository.downloads.first { it.any { d -> d.state == DownloadState.COMPLETED } }.single())
            assertNull(f.dao.forSong(f.song.id))
            assertEquals(0, f.dao.updateProgress(completed.requestId, "QUEUED", 0, null, null, null, 0))
            assertFalse(File(Uri.parse(completed.localUri).path!!).exists())
        }
    }

    @Test fun expiredSessionDoesNotStayQueuedOrAccessServer() = runBlocking {
        fixture { f ->
            f.store.save(f.credentials, f.profile) // New login invalidates the captured session token.
            f.repository.enqueue(listOf(f.song))
            val failed = f.awaitState("FAILED")
            assertTrue(failed.failureReason!!.contains("登录"))
            assertTrue(f.server.requests.isEmpty())
        }
    }

    @Test fun schedulerSubmissionFailureIsVisible() = runBlocking {
        fixture { f ->
            val failing = object : DownloadScheduler {
                override fun observe() = flowOf(emptyList<WorkInfo>())
                override suspend fun enqueue(request: OneTimeWorkRequest) { error("Test scheduler rejected request") }
                override suspend fun cancel(id: UUID) {}
                override suspend fun update(request: OneTimeWorkRequest) {}
            }
            val repo = DownloadRepository(f.context, f.session, f.preferences, failing)
            assertEquals(1, repo.enqueue(listOf(f.song)).failedCount)
            assertEquals("FAILED", f.dao.forSong(f.song.id)?.state)
            assertTrue(f.dao.forSong(f.song.id)!!.failureReason!!.contains("提交"))
        }
    }

    @Test fun networkSettingUpdatesExistingRequestWithoutChangingAccountOrId() = runBlocking {
        fixture { f ->
            val scheduler = RecordingScheduler()
            val repo = DownloadRepository(f.context, f.session, f.preferences, scheduler)
            repo.enqueue(listOf(f.song))
            val original = scheduler.latest!!
            repo.applyNetworkPolicy(true)
            assertEquals(original.id, scheduler.latest!!.id)
            assertEquals(NetworkType.UNMETERED, scheduler.latest!!.workSpec.constraints.requiredNetworkType)
            repo.applyNetworkPolicy(false)
            assertEquals(NetworkType.CONNECTED, scheduler.latest!!.workSpec.constraints.requiredNetworkType)
            assertEquals(f.session.accountKey, scheduler.latest!!.workSpec.input.getString(DownloadWorker.KEY_ACCOUNT))
            assertEquals(f.session.token, scheduler.latest!!.workSpec.input.getString(DownloadWorker.KEY_SESSION))
        }
    }

    private suspend fun fixture(code: Int = 200, block: suspend (Fixture) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = CredentialsStore(context)
        assumeTrue("Use an empty test app; never replace a user's login", store.credentials.first() == null)
        val preferences = AppPreferences(context)
        val originalWifi = preferences.settings.first().wifiOnlyDownloads
        val server = AudioServer(code)
        val url = "http://127.0.0.1:${server.port}/"
        val credentials = ServerCredentials(url, "download-test-${UUID.randomUUID()}", "test", allowInsecureHttp = true)
        val profile = ServerProfile(url, credentials.username, "test", "1", "1.16.1", true, allowInsecureHttp = true)
        val key = accountStorageKey(url, credentials.username)
        val database = DatabaseFactory.create(context, key)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val scheduler = WorkDownloadScheduler(context)
        try {
            store.save(credentials, profile)
            preferences.setWifiOnlyDownloads(false)
            val session = AccountSession(credentials, profile, store.sessionToken.first()!!, database)
            val song = Song("test-audio", "Test audio", coverArtId = "test-cover", suffix = "wav", sizeBytes = server.audio.size.toLong())
            database.mediaDao().upsertSongs(listOf(song.toEntity()))
            val repository = DownloadRepository(context, session, preferences, scheduler)
            scope.launch { repository.monitor() }
            block(Fixture(context, store, preferences, credentials, profile, session, song, server, repository, scope))
        } finally {
            scope.cancel()
            database.downloadDao().pauseActiveDownloads()
            database.downloadDao().all().forEach { scheduler.cancel(UUID.fromString(it.requestId)) }
            WorkManager.getInstance(context).cancelAllWorkByTag(DownloadWorker::class.java.name).result.get()
            store.clear()
            server.close()
            delay(500)
            database.close()
            context.deleteDatabase("cleartune_account_$key.db")
            File(context.filesDir, "accounts/$key").deleteRecursively()
            preferences.setWifiOnlyDownloads(originalWifi)
        }
    }

    private class Fixture(val context: android.content.Context, val store: CredentialsStore,
        val preferences: AppPreferences, val credentials: ServerCredentials, val profile: ServerProfile,
        val session: AccountSession, val song: Song, val server: AudioServer,
        val repository: DownloadRepository, val scope: CoroutineScope) {
        val dao = session.database.downloadDao()
        suspend fun awaitState(state: String): DownloadEntity = withTimeout(45_000) {
            while (true) {
                val row = dao.forSong(song.id)
                if (row?.state == state) return@withTimeout row
                if (row?.state == "FAILED") error("Unexpected failure: ${row.failureReason}")
                delay(50)
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
    }

    private class RecordingScheduler : DownloadScheduler {
        var latest: OneTimeWorkRequest? = null
        override fun observe() = flowOf(latest?.let { listOf(WorkInfo(it.id, WorkInfo.State.ENQUEUED, it.tags)) } ?: emptyList())
        override suspend fun enqueue(request: OneTimeWorkRequest) { latest = request }
        override suspend fun update(request: OneTimeWorkRequest) { latest = request }
        override suspend fun cancel(id: UUID) {}
    }

    private class AudioServer(code: Int) {
        @Volatile var responseCode = code
        @Volatile var coverResponseCode = 200
        val cover = java.io.ByteArrayOutputStream().use { bytes ->
            val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.BLUE)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)
            bitmap.recycle()
            bytes.toByteArray()
        }
        @Volatile var coverPayload = cover
        private val socket = ServerSocket(0)
        val port = socket.localPort
        val requests = CopyOnWriteArrayList<Long>()
        val audio = ByteBuffer.allocate(320_044).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(320_036); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(16_000); putInt(32_000)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(320_000)
        }.array()
        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) {
                        runCatching {
                            client.use {
                                val reader = it.getInputStream().bufferedReader()
                                val requestLine = reader.readLine().orEmpty()
                                val isCover = requestLine.contains("getCoverArt")
                                var range = 0L
                                while (true) {
                                    val line = reader.readLine() ?: break
                                    if (line.isEmpty()) break
                                    if (line.startsWith("Range:", true)) range = line.substringAfter("bytes=").substringBefore("-").toLong()
                                }
                                val code = if (isCover) coverResponseCode else responseCode
                                if (!isCover) requests.add(range)
                                val output = it.getOutputStream()
                                if (code != 200) {
                                    output.write("HTTP/1.1 $code Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                                } else if (isCover) {
                                    val imageBytes = coverPayload
                                    output.write(("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\n" +
                                        "Content-Length: ${imageBytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                                    output.write(imageBytes)
                                } else {
                                    val status = if (range > 0) 206 else 200
                                    val contentRange = if (range > 0) "Content-Range: bytes $range-${audio.lastIndex}/${audio.size}\r\n" else ""
                                    output.write(("HTTP/1.1 $status OK\r\nContent-Type: audio/wav\r\n" +
                                        "Content-Length: ${audio.size - range}\r\n" + contentRange + "Connection: close\r\n\r\n").toByteArray())
                                    var offset = range.toInt()
                                    while (offset < audio.size) {
                                        val size = minOf(4096, audio.size - offset)
                                        output.write(audio, offset, size); output.flush()
                                        offset += size
                                        Thread.sleep(25)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        fun close() { runCatching { socket.close() } }
    }
}
