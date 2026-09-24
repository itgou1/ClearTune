package com.cleartune.app

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.cleartune.app.artwork.ArtworkCache
import com.cleartune.app.artwork.artworkCacheKey
import com.cleartune.app.auth.AccountSession
import com.cleartune.app.library.MusicRepository
import com.cleartune.app.library.MusicViewModel
import com.cleartune.core.database.DatabaseFactory
import com.cleartune.core.datastore.AppPreferences
import com.cleartune.core.model.*
import com.cleartune.core.network.OpenSubsonicApiFactory
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlaylistCoverLoadingTest {
    @get:Rule val ui = createComposeRule()

    @Test fun cachedSmallCoverRemainsVisibleWhenHdFails() = checkLoading(cached = true, hdSuccess = false)
    @Test fun missingSmallCacheAutomaticallyLoadsThumbnailWhenHdFails() = checkLoading(cached = false, hdSuccess = false)
    @Test fun successfulHdAutomaticallyReplacesSmallCover() = checkLoading(cached = true, hdSuccess = true)

    private fun checkLoading(cached: Boolean, hdSuccess: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val server = CoverServer(hdSuccess)
        val url = "http://127.0.0.1:${server.port}/"
        val username = "cover-${UUID.randomUUID()}"
        val account = accountStorageKey(url, username)
        val database = DatabaseFactory.create(context, account)
        val session = AccountSession(ServerCredentials(url, username, "test", true),
            ServerProfile(url, username, "test", "1", "1.16.1", true), "test", database)
        val repository = MusicRepository(session, OpenSubsonicApiFactory())
        lateinit var viewModel: MusicViewModel
        ui.runOnIdle { viewModel = MusicViewModel(repository, AppPreferences(context), session, context) }
        try {
            if (cached) runBlocking {
                val key = artworkCacheKey(account, "cover", 192)
                val result = context.imageLoader.execute(ImageRequest.Builder(context)
                    .data(repository.coverArtUrl("cover", 192)).size(192)
                    .memoryCacheKey("$key:${ArtworkCache.generation}:false").diskCacheKey(key).build())
                assertTrue(result is SuccessResult)
            }
            ui.setContent {
                CoverArt("cover", "Playlist artwork", viewModel, Modifier.size(220.dp),
                    requestSize = 768, previewSize = 192)
            }
            awaitColor(Color.Red)
            ui.waitUntil(10_000) { server.hdRequested.count == 0L }
            server.releaseHd.countDown()
            ui.waitUntil(10_000) { server.hdCompleted.count == 0L }
            if (hdSuccess) awaitColor(Color.Blue) else {
                // Allow the failed image request to reach Compose, not just the test server.
                Thread.sleep(500)
                ui.waitForIdle()
                awaitColor(Color.Red)
            }
            assertEquals("Thumbnail should be reused, not downloaded again", 1, server.smallRequests.get())
        } finally {
            server.close()
            session.revoke()
            ui.runOnIdle { viewModel.viewModelScope.cancel() }
            database.close()
            context.deleteDatabase("cleartune_account_$account.db")
        }
    }

    private fun awaitColor(expected: Color) {
        ui.waitUntil(10_000) {
            val image = ui.onNodeWithContentDescription("Playlist artwork").captureToImage().toPixelMap()
            val center = image[image.width / 2, image.height / 2]
            kotlin.math.abs(center.red - expected.red) < 0.02f &&
                kotlin.math.abs(center.blue - expected.blue) < 0.02f &&
                kotlin.math.abs(center.green - expected.green) < 0.02f
        }
    }

    private class CoverServer(private val hdSuccess: Boolean) : AutoCloseable {
        private val server = ServerSocket(0)
        val port = server.localPort
        val smallRequests = AtomicInteger()
        val hdRequested = CountDownLatch(1)
        val releaseHd = CountDownLatch(1)
        val hdCompleted = CountDownLatch(1)
        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) {
                        runCatching { socket.use {
                            val reader = it.getInputStream().bufferedReader()
                            val request = reader.readLine() ?: return@use
                            while (!reader.readLine().isNullOrEmpty()) { }
                            val uri = Uri.parse("http://localhost" + request.split(" ")[1])
                            if (uri.path?.contains("getCoverArt") != true) {
                                val body = """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":50,"message":"Unused test endpoint"}}}""".toByteArray()
                                it.getOutputStream().apply {
                                    write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                    write(body)
                                }
                                return@use
                            }
                            val size = uri.getQueryParameter("size")
                            val hd = size == "768"
                            if (hd) {
                                hdRequested.countDown()
                                releaseHd.await(15, TimeUnit.SECONDS)
                            } else smallRequests.incrementAndGet()
                            val success = !hd || hdSuccess
                            val bytes = if (!success) byteArrayOf() else ByteArrayOutputStream().use { output ->
                                val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
                                bitmap.eraseColor(if (hd) android.graphics.Color.BLUE else android.graphics.Color.RED)
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                                bitmap.recycle()
                                output.toByteArray()
                            }
                            it.getOutputStream().apply {
                                write(("HTTP/1.1 ${if (success) "200 OK" else "404 Not Found"}\r\n" +
                                    "Content-Type: image/png\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                                write(bytes)
                                flush()
                            }
                            if (hd) hdCompleted.countDown()
                        } }
                    }
                }
            }
        }
        override fun close() { releaseHd.countDown(); server.close() }
    }
}
