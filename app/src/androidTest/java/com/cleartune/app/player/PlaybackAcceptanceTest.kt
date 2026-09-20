package com.cleartune.app.player

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.cleartune.app.MainActivity
import com.cleartune.app.auth.AccountViewModels
import com.cleartune.app.auth.AuthUiState
import com.cleartune.app.auth.AuthViewModel
import com.cleartune.app.download.DownloadViewModel
import com.cleartune.app.library.MusicViewModel
import com.cleartune.core.database.DatabaseFactory
import com.cleartune.core.datastore.AppPreferences
import com.cleartune.core.datastore.CredentialsStore
import com.cleartune.core.datastore.MobileAudioQuality
import com.cleartune.core.datastore.ThemeMode
import com.cleartune.core.model.DownloadState
import com.cleartune.core.model.PlaybackMode
import com.cleartune.core.model.accountStorageKey
import com.cleartune.core.player.PlaybackStatus
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Opt-in acceptance on an empty disposable device. The cold-start pair runs in separate processes. */
class PlaybackAcceptanceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var auth: AuthViewModel
    private lateinit var player: PlayerViewModel
    private lateinit var music: MusicViewModel
    private lateinit var downloads: DownloadViewModel

    @Before fun optIn() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("playbackAcceptance") == "true")
        instrumentation.runOnMainSync { auth = ViewModelProvider(ui.activity)[AuthViewModel::class.java] }
        await("authentication restore") { auth.state.value !is AuthUiState.Restoring }
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
    }

    @Test fun mobileCachedPlaybackSurvivesSignalLossAndRecovery() = connected { server ->
        shell("svc wifi disable")
        shell("svc data enable")
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        await("cellular transport") {
            connectivity.getNetworkCapabilities(connectivity.activeNetwork)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        }
        runBlocking { AppPreferences(context).setMobileAudioQuality(MobileAudioQuality.RATE_192) }
        instrumentation.runOnMainSync { player.play(music.libraryState.value.songs) }
        awaitPlaying()
        await("full audio buffer") { player.state.value.bufferedPositionMs >= 11_500 }
        assertTrue(server.requests.any { it.contains("stream") && it.contains("maxBitRate=192") })
        server.online = false
        server.disconnectClients()
        shell("svc data disable")
        await("no network") { connectivity.activeNetwork == null }
        instrumentation.runOnMainSync { player.play(music.libraryState.value.songs) }
        awaitPlaying()
        val position = player.state.value.positionMs
        await("offline playback progress") { player.state.value.positionMs > position + 1_000 }
        server.online = true
        shell("svc data enable")
        await("network recovery") { connectivity.activeNetwork != null }
        instrumentation.runOnMainSync { player.play(music.libraryState.value.songs, 1) }
        awaitPlaying("accept-1")
    }

    @Test fun failedStreamCanBeRetriedAfterConnectionReturns() = connected { server ->
        server.online = false
        instrumentation.runOnMainSync { player.play(music.libraryState.value.songs) }
        await("network failure", 30_000) { player.state.value.status == PlaybackStatus.ERROR }
        server.online = true
        // The visible play button must actually retry a failed preparation.
        instrumentation.runOnMainSync { player.togglePlayPause() }
        awaitPlaying()
    }

    @Test fun externalPreviousSkipsAfterFiveSecondsAndRepeatedly() = connected { _ ->
        instrumentation.runOnMainSync {
            player.play(music.libraryState.value.songs, 2)
            player.setMode(PlaybackMode.REPEAT_ALL)
        }
        awaitPlaying("accept-2")
        shell("input keyevent KEYCODE_HOME")
        // Each operation used to restart the same song after the three-second threshold.
        for (id in listOf("accept-1", "accept-0", "accept-2")) {
            instrumentation.runOnMainSync { player.seekTo(5_000) }
            await("five seconds before previous") { player.state.value.positionMs >= 5_000 }
            shell("cmd media_session dispatch previous")
            awaitPlaying(id)
            android.util.Log.i("ClearTunePrev", "previous at five seconds -> $id")
        }
        for (id in listOf("accept-1", "accept-0", "accept-2")) {
            shell("cmd media_session dispatch previous")
            awaitPlaying(id)
        }
    }

    @Test fun externalPreviousMatchesPhoneInEveryPlaybackMode() = connected { _ ->
        instrumentation.runOnMainSync { player.play(music.libraryState.value.songs, 1) }
        awaitPlaying("accept-1")
        for (mode in PlaybackMode.entries) {
            instrumentation.runOnMainSync { player.setMode(mode); player.playAt(1) }
            awaitPlaying("accept-1")
            instrumentation.runOnMainSync { player.seekTo(5_000) }
            await("phone comparison position") { player.state.value.positionMs >= 5_000 }
            instrumentation.runOnMainSync { player.previous() }
            await("phone previous in $mode") { player.state.value.currentSong?.id != "accept-1" }
            val expectedId = player.state.value.currentSong!!.id
            // Keep the same queue/shuffle order when comparing the external command.
            instrumentation.runOnMainSync { player.playAt(1) }
            awaitPlaying("accept-1")
            instrumentation.runOnMainSync { player.seekTo(5_000) }
            await("external comparison position") { player.state.value.positionMs >= 5_000 }
            shell("cmd media_session dispatch previous")
            awaitPlaying(expectedId)
            android.util.Log.i("ClearTunePrev", "$mode: external and phone previous -> $expectedId")
        }
    }

    @Test fun externalPreviousAtQueueStartDoesNotRestartOrResumePausedAudio() = connected { _ ->
        val songs = music.libraryState.value.songs
        for (queue in listOf(songs, songs.take(1))) {
            instrumentation.runOnMainSync {
                player.setMode(PlaybackMode.SEQUENTIAL)
                player.play(queue)
            }
            awaitPlaying("accept-0")
            instrumentation.runOnMainSync { player.togglePlayPause(); player.seekTo(5_000) }
            await("paused at five seconds") {
                player.state.value.status == PlaybackStatus.PAUSED && player.state.value.positionMs >= 5_000
            }
            val position = player.state.value.positionMs
            repeat(3) { shell("cmd media_session dispatch previous") }
            val dispatchedAt = android.os.SystemClock.elapsedRealtime()
            ui.waitUntil(5_000) { android.os.SystemClock.elapsedRealtime() - dispatchedAt >= 1_000 }
            assertEquals("accept-0", player.state.value.currentSong?.id)
            assertEquals(PlaybackStatus.PAUSED, player.state.value.status)
            assertEquals(position, player.state.value.positionMs)
            android.util.Log.i("ClearTunePrev", "queue size=${queue.size}: paused boundary preserved at $position ms")
        }
    }

    @Test fun playbackContinuesInBackgroundAndScreenOff() = connected { _ ->
        instrumentation.runOnMainSync { player.play(music.libraryState.value.songs) }
        awaitPlaying()
        shell("input keyevent KEYCODE_HOME")
        var position = player.state.value.positionMs
        await("background progress") { player.state.value.positionMs > position + 1_000 }
        shell("input keyevent KEYCODE_SLEEP")
        position = player.state.value.positionMs
        await("screen-off progress") { player.state.value.positionMs > position + 1_000 }
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        shell("am start -n ${context.packageName}/.MainActivity")
        awaitPlaying()
    }

    @Test fun rapidQueueEditsSeekingAndReturningToLibraryKeepPlaybackConsistent() = connected { _ ->
        val songs = music.libraryState.value.songs
        instrumentation.runOnMainSync {
            player.play(songs)
            player.setMode(PlaybackMode.SEQUENTIAL)
        }
        awaitPlaying()
        for (index in listOf(1, 2, 0, 2, 1)) {
            instrumentation.runOnMainSync { player.playAt(index); player.seekTo(3_000) }
            awaitPlaying(songs[index].id)
        }
        instrumentation.runOnMainSync { player.move(2, 0) }
        await("queue move") { player.state.value.queue.map { it.id } == listOf("accept-2", "accept-0", "accept-1") }
        assertEquals("accept-1", player.state.value.currentSong?.id)
        var undo: Long? = null
        instrumentation.runOnMainSync { undo = player.removeUndoable(1) }
        await("queue removal") { player.state.value.queue.size == 2 }
        instrumentation.runOnMainSync { player.undoQueueMutation(undo!!) }
        await("queue undo") { player.state.value.queue.size == 3 }
        instrumentation.runOnMainSync { player.seekTo(5_000) }
        await("seek target") { player.state.value.positionMs >= 5_000 }
        ui.onNode(hasText("音乐库") and hasClickAction()).performClick()
        awaitPlaying("accept-1")
        ui.onNode(hasText("音乐库") and hasClickAction()).assertExists()
        assertEquals(listOf("accept-2", "accept-0", "accept-1"), player.state.value.queue.map { it.id })
    }

    @Test fun prepareDownloadForColdRestart() {
        val server = AcceptanceAudioServer()
        try {
            connect(server)
            runBlocking {
                AppPreferences(context).apply {
                    setWifiOnlyDownloads(false)
                    setMobileAudioQuality(MobileAudioQuality.RATE_128)
                    setThemeMode(ThemeMode.DARK)
                }
            }
            val songs = music.libraryState.value.songs
            instrumentation.runOnMainSync { downloads.download(songs); player.play(songs, 1) }
            await("three completed downloads", 45_000) {
                downloads.downloads.value.count { it.state == DownloadState.COMPLETED } == 3
            }
            awaitPlaying("accept-1")
            instrumentation.runOnMainSync {
                player.setMode(PlaybackMode.REPEAT_ALL)
                player.seekTo(4_000)
            }
            await("position before restart") { player.state.value.positionMs >= 4_000 }
            instrumentation.runOnMainSync { player.togglePlayPause(); player.persistNow() }
            val credentials = runBlocking { CredentialsStore(context).credentials.first()!! }
            val db = DatabaseFactory.create(context, accountStorageKey(credentials.baseUrl, credentials.username))
            try { await("persisted queue") { runBlocking { db.queueDao().queue().any { it.isCurrent && it.songId == "accept-1" && it.playbackPositionMs >= 4_000 } } } }
            finally { db.close() }
            File(context.filesDir, "playback-acceptance-cold-start").writeText("ready")
        } finally { server.close() }
        // Intentionally retain this synthetic account, downloads and queue for the next process.
    }

    @Test fun downloadedQueueAndSettingsSurviveColdRestartWithServerStopped() {
        assertTrue(File(context.filesDir, "playback-acceptance-cold-start").exists())
        assertTrue(auth.state.value is AuthUiState.Connected)
        assertTrue((auth.state.value as AuthUiState.Connected).restoredOffline)
        models()
        await("restored queue") { player.state.value.queue.size == 3 }
        assertEquals("accept-1", player.state.value.currentSong?.id)
        assertTrue("Saved position must survive process death", player.state.value.positionMs >= 3_500)
        assertEquals(PlaybackMode.REPEAT_ALL, player.state.value.mode)
        runBlocking {
            val settings = AppPreferences(context).settings.first()
            assertEquals(MobileAudioQuality.RATE_128, settings.mobileAudioQuality)
            assertEquals(ThemeMode.DARK, settings.themeMode)
        }
        instrumentation.runOnMainSync { player.togglePlayPause() }
        awaitPlaying("accept-1")
        val position = player.state.value.positionMs
        await("cold-start offline audio progress") { player.state.value.positionMs > position + 1_000 }
        instrumentation.runOnMainSync { player.playAt(2) }
        awaitPlaying("accept-2")
        logout()
        File(context.filesDir, "playback-acceptance-cold-start").delete()
    }

    private fun connected(block: (AcceptanceAudioServer) -> Unit) {
        val server = AcceptanceAudioServer()
        try { connect(server); block(server) } finally {
            server.online = true
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            shell("svc data enable")
            shell("svc wifi enable")
            if (::player.isInitialized) logout()
            server.close()
        }
    }

    private fun connect(server: AcceptanceAudioServer) {
        assertTrue("Use an empty disposable device", auth.state.value is AuthUiState.Login)
        instrumentation.runOnMainSync { auth.connect(server.url, "acceptance-${UUID.randomUUID()}", "test-only", true) }
        await("login") { auth.state.value is AuthUiState.Connected }
        models()
        await("test library") { music.libraryState.value.songs.size == 3 }
    }

    private fun models() = instrumentation.runOnMainSync {
        val owner = ViewModelProvider(ui.activity)[AccountViewModels::class.java]
        val provider = ViewModelProvider(owner.store, ui.activity.defaultViewModelProviderFactory, ui.activity.defaultViewModelCreationExtras)
        music = provider[MusicViewModel::class.java]
        player = provider[PlayerViewModel::class.java]
        downloads = provider[DownloadViewModel::class.java]
    }

    private fun logout() {
        instrumentation.runOnMainSync {
            val owner = ViewModelProvider(ui.activity)[AccountViewModels::class.java]
            auth.logout { try { player.endSession() } finally { owner.clearSession() } }
        }
        await("logout") { auth.state.value is AuthUiState.Login }
    }

    private fun awaitPlaying(songId: String? = null) = await("playing $songId", 20_000) {
        player.state.value.status == PlaybackStatus.PLAYING && (songId == null || player.state.value.currentSong?.id == songId)
    }

    private fun await(label: String, timeout: Long = 20_000, condition: () -> Boolean) {
        // Advance the Compose test clock as well as waiting for the actual service.
        // Otherwise the connected screen never subscribes to its WhileSubscribed flows.
        try { ui.waitUntil(timeout) { condition() } }
        catch (error: Exception) {
            throw AssertionError("Timed out: $label; player=${if (::player.isInitialized) player.state.value else "not connected"}", error)
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }
}

private class AcceptanceAudioServer {
    private val server = ServerSocket(0)
    val url = "http://127.0.0.1:${server.localPort}/"
    @Volatile var online = true
    val requests = CopyOnWriteArrayList<String>()
    private val clients = CopyOnWriteArrayList<Socket>()
    private val audio = ByteBuffer.allocate(384_044).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(384_036); put("WAVEfmt ".toByteArray())
        putInt(16); putShort(1); putShort(1); putInt(16_000); putInt(32_000)
        putShort(2); putShort(16); put("data".toByteArray()); putInt(384_000)
    }.array()
    private val songs = (0..2).joinToString(",") {
        """{"id":"accept-$it","title":"Acceptance $it","artist":"Test Artist","album":"Test Album","albumId":"test-album","duration":12,"suffix":"wav","size":${audio.size},"track":${it + 1}}"""
    }
    private val json = """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"test","albumList2":{"album":[]},"artists":{"index":[]},"playlists":{"playlist":[]},"searchResult3":{"song":[$songs]},"starred2":{"song":[]},"genres":{"genre":[]},"musicFolders":{"musicFolder":[]},"playQueue":{"entry":[]}}}""".toByteArray()
    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                clients.add(socket)
                thread(isDaemon = true) {
                    try {
                        socket.use {
                            it.soTimeout = 5_000
                            val reader = it.getInputStream().bufferedReader()
                            val request = reader.readLine().orEmpty()
                            requests.add(request)
                            var range = 0
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                                if (line.startsWith("Range:", true)) range = line.substringAfter("bytes=").substringBefore('-').toInt()
                            }
                            if (!online) return@thread
                            val isAudio = request.contains("/stream") || request.contains("/download")
                            val bytes = if (isAudio) audio else json
                            val from = if (isAudio) range.coerceAtMost(bytes.size) else 0
                            val status = if (from > 0) "206 Partial Content" else "200 OK"
                            val type = if (isAudio) "audio/wav" else "application/json"
                            val contentRange = if (from > 0) "Content-Range: bytes $from-${bytes.lastIndex}/${bytes.size}\r\n" else ""
                            val output = it.getOutputStream()
                            output.write("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size - from}\r\n${contentRange}Connection: close\r\n\r\n".toByteArray())
                            output.write(bytes, from, bytes.size - from)
                            output.flush()
                        }
                    } catch (_: Exception) { /* Disconnections are intentional in these tests. */ }
                    finally { clients.remove(socket) }
                }
            }
        }
    }
    fun disconnectClients() { clients.forEach { runCatching { it.close() } } }
    fun close() { server.close(); disconnectClients() }
}
