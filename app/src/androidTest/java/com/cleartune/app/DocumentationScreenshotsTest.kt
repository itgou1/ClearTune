package com.cleartune.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.LinearGradient
import android.graphics.Shader
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.cleartune.app.auth.AccountViewModels
import com.cleartune.app.auth.AuthUiState
import com.cleartune.app.auth.AuthViewModel
import com.cleartune.app.library.MusicViewModel
import com.cleartune.app.player.PlayerViewModel
import com.cleartune.core.datastore.AppPreferences
import com.cleartune.core.datastore.ThemeMode
import com.cleartune.core.database.DatabaseFactory
import com.cleartune.core.model.accountStorageKey
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in, real app screenshots with a synthetic local library. Never uses a real account. */
class DocumentationScreenshotsTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun captureCurrentPages() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("documentationScreenshots") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        lateinit var auth: AuthViewModel
        ui.runOnIdle { auth = ViewModelProvider(ui.activity)[AuthViewModel::class.java] }
        ui.waitUntil(30_000) { auth.state.value !is AuthUiState.Restoring }
        assumeTrue("Use an empty test app; do not replace a real login", auth.state.value is AuthUiState.Login)
        val preferences = AppPreferences(context)
        val originalTheme = runBlocking { preferences.settings.first().themeMode }
        val server = ServerSocket(0)
        val titles = listOf("晨光来信", "城市漫步", "海边的风", "夜色温柔", "午后片刻", "远山回声")
        val albums = titles.mapIndexed { i, title ->
            """{"id":"a$i","name":"$title","artist":"示例音乐人","coverArt":"${600 + i}","songCount":4,"duration":840,"created":"2026-09-01T00:00:00Z"}"""
        }.joinToString(",")
        val songs = (0 until 24).map { i ->
            """{"id":"s$i","title":"${titles[i % 6]} · ${i / 6 + 1}","artist":"示例音乐人","album":"${titles[i % 6]}","albumId":"a${i % 6}","coverArt":"${600 + i % 6}","duration":210,"suffix":"flac","genre":"${if (i >= 17) "Rock" else "Pop"}","playCount":${24 - i}}"""
        }
        val covers = (0 until 6).map(::cover)
        val response = """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"test","albumList2":{"album":[$albums]},"artists":{"index":[]},"playlists":{"playlist":[{"id":"p1","name":"清晨慢慢听","songCount":8,"duration":1680,"coverArt":"601"},{"id":"p2","name":"通勤路上","songCount":12,"duration":2520,"coverArt":"602"}]},"searchResult3":{"song":[${songs.joinToString(",")}]},"starred2":{"song":[${songs.take(8).joinToString(",")}]},"genres":{"genre":[]},"musicFolders":{"musicFolder":[]},"playQueue":{"entry":[]}}}""".toByteArray()
        val responder = thread(isDaemon = true) {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    runCatching {
                        client.use { socket ->
                            socket.soTimeout = 5_000
                            val reader = socket.getInputStream().bufferedReader()
                            val request = reader.readLine().orEmpty()
                            while (!reader.readLine().isNullOrEmpty()) { }
                            val isCover = request.contains("getCoverArt")
                            val index = Regex("[?&]id=(\\d+)").find(request)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                            val body = if (isCover) covers[index % covers.size] else response
                            val type = if (isCover) "image/png" else "application/json"
                            socket.getOutputStream().apply {
                                write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                write(body)
                                flush()
                            }
                        }
                    }
                }
            }
        }
        try {
            runBlocking { preferences.setThemeMode(ThemeMode.LIGHT) }
            ui.runOnIdle { auth.connect("http://127.0.0.1:${server.localPort}/", "demo", "demo-only", true) }
            ui.waitUntil(20_000) { auth.state.value is AuthUiState.Connected }
            lateinit var music: MusicViewModel
            ui.runOnIdle {
                val owner = ViewModelProvider(ui.activity)[AccountViewModels::class.java]
                music = ViewModelProvider(owner.store, ui.activity.defaultViewModelProviderFactory,
                    ui.activity.defaultViewModelCreationExtras)[MusicViewModel::class.java]
            }
            ui.waitUntil(30_000) { music.libraryState.value.songs.size == 24 && !music.libraryState.value.isRefreshing }
            // Supply fictional listening history so both recommendation cards show their full layout.
            runBlocking {
                val database = DatabaseFactory.create(context,
                    accountStorageKey("http://127.0.0.1:${server.localPort}/", "demo"))
                try {
                    val now = System.currentTimeMillis()
                    val rows = (1..8).map { i ->
                        checkNotNull(database.mediaDao().song("s$i")).copy(
                            playCount = (30 - i).toLong(),
                            lastPlayedAt = now - 2 * 86_400_000L,
                            starredAt = now,
                        )
                    }
                    val longAbsent = (17..21).map { i ->
                        checkNotNull(database.mediaDao().song("s$i")).copy(
                            lastPlayedAt = now - 40 * 86_400_000L,
                        )
                    }
                    database.mediaDao().upsertSongs(rows + longAbsent)
                    ui.waitUntil(10_000) { music.libraryState.value.songs.count { it.starredAt != null } == 8 }
                } finally { database.close() }
            }
            ui.waitUntil(10_000) { music.recommendations.value.any { it.id == "frequent" && it.songs.isNotEmpty() } }
            ui.waitUntil(10_000) {
                music.recommendations.value.map { it.id }.containsAll(
                    listOf("from-favorites", "new-taste", "long-absent"))
            }
            fun capture(name: String) {
                ui.waitForIdle()
                Thread.sleep(1_500) // Allow real cover requests and the entrance animation to settle.
                val folder = File(context.getExternalFilesDir(null), "documentation").apply { mkdirs() }
                val bitmap = ui.onRoot().captureToImage().asAndroidBitmap()
                File(folder, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            capture("ui-home-v1.3.3.png")
            ui.onNodeWithText("发现音乐").performClick()
            ui.onNodeWithText("从喜欢出发").assertExists()
            ui.onNodeWithText("换个口味").assertExists()
            ui.onNodeWithText("好久不见").assertExists()
            capture("ui-discovery-redesign.png")
            ui.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
            ui.onNodeWithText("我的").performClick()
            capture("ui-mine-v1.3.3.png")
        } finally {
            if (auth.state.value is AuthUiState.Connected) {
                ui.runOnIdle {
                    val owner = ViewModelProvider(ui.activity)[AccountViewModels::class.java]
                    val player = ViewModelProvider(owner.store, ui.activity.defaultViewModelProviderFactory,
                        ui.activity.defaultViewModelCreationExtras)[PlayerViewModel::class.java]
                    auth.logout { try { player.endSession() } finally { owner.clearSession() } }
                }
                ui.waitUntil(15_000) { auth.state.value is AuthUiState.Login }
            }
            server.close()
            responder.join(1_000)
            runBlocking { preferences.setThemeMode(originalTheme) }
        }
    }

    /** Synthetic artwork rendered on Android Canvas, with no photos or third-party assets. */
    private fun cover(index: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun color(hex: String) { paint.color = Color.parseColor(hex) }
        fun sky(top: String, bottom: String) {
            paint.shader = LinearGradient(0f, 0f, 320f, 320f,
                Color.parseColor(top), Color.parseColor(bottom), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, 320f, 320f, paint)
            paint.shader = null
        }
        fun polygon(hex: String, vararg points: Float) {
            color(hex)
            val path = Path().apply {
                moveTo(points[0], points[1])
                for (i in 2 until points.size step 2) lineTo(points[i], points[i + 1])
                close()
            }
            canvas.drawPath(path, paint)
        }
        // Each album has its own subject and composition; avoid a wall of repeated symbols.
        when (index) {
            0 -> { // Dawn, wide quiet sky and a single sun.
                sky("#E7B69B", "#F5E5CC")
                color("#FFF2D5"); canvas.drawCircle(224f, 99f, 31f, paint)
                polygon("#B69897", 0f, 237f, 72f, 191f, 148f, 226f, 245f, 185f, 320f, 218f, 320f, 320f, 0f, 320f)
                polygon("#787E86", 0f, 278f, 99f, 240f, 204f, 277f, 320f, 244f, 320f, 320f, 0f, 320f)
            }
            1 -> { // City evening, sparse architecture and warm windows.
                sky("#91A8B6", "#DBC9B8")
                color("#637585"); canvas.drawRect(12f, 131f, 88f, 320f, paint)
                color("#485D70"); canvas.drawRect(100f, 77f, 171f, 320f, paint)
                color("#718692"); canvas.drawRect(185f, 156f, 266f, 320f, paint)
                color("#384C60"); canvas.drawRect(274f, 110f, 320f, 320f, paint)
                color("#EACB92")
                for (y in listOf(114f, 160f, 206f)) canvas.drawRect(119f, y, 132f, y + 19f, paint)
                canvas.drawRect(214f, 190f, 239f, 205f, paint)
                polygon("#C7ADA0", 0f, 296f, 320f, 270f, 320f, 320f, 0f, 320f)
            }
            2 -> { // Sea breeze, horizontal water and one small sail.
                sky("#BCD6D5", "#F3EBDC")
                color("#6DABA9"); canvas.drawRect(0f, 160f, 320f, 320f, paint)
                polygon("#46898E", 0f, 228f, 105f, 207f, 223f, 235f, 320f, 220f, 320f, 320f, 0f, 320f)
                polygon("#DDEAE0", 197f, 194f, 197f, 116f, 152f, 194f)
                polygon("#EEE3C7", 203f, 194f, 203f, 136f, 231f, 194f)
                polygon("#365B69", 161f, 203f, 229f, 203f, 215f, 217f, 174f, 217f)
                color("#A6CBBD"); paint.strokeWidth = 3f
                canvas.drawLine(30f, 261f, 114f, 261f, paint)
                canvas.drawLine(172f, 286f, 287f, 286f, paint)
            }
            3 -> { // Night, a narrow moon and a restrained treeline.
                sky("#252F4D", "#677184")
                color("#E8DDC2"); canvas.drawCircle(231f, 81f, 25f, paint)
                color("#303B56"); canvas.drawCircle(241f, 73f, 24f, paint)
                polygon("#38495B", 0f, 240f, 113f, 208f, 230f, 245f, 320f, 213f, 320f, 320f, 0f, 320f)
                for ((x, height) in listOf(43f to 126f, 88f to 89f, 269f to 111f)) {
                    polygon("#1F3544", x - 26f, 281f, x, 281f - height, x + 26f, 281f)
                }
            }
            4 -> { // Botanical paper, generous empty space around a single branch.
                sky("#EEE4D0", "#D9D6BE")
                color("#75876B"); paint.strokeWidth = 4f
                canvas.drawLine(130f, 291f, 199f, 76f, paint)
                for ((x, y) in listOf(153f to 220f, 174f to 150f)) {
                    color("#788D72")
                    canvas.save(); canvas.rotate(-32f, x, y)
                    canvas.drawOval(x - 59f, y - 23f, x + 4f, y + 10f, paint)
                    canvas.restore()
                    color("#9BAB84")
                    canvas.save(); canvas.rotate(24f, x, y - 31f)
                    canvas.drawOval(x, y - 48f, x + 68f, y - 17f, paint)
                    canvas.restore()
                }
            }
            else -> { // Mountain echoes, angular peaks instead of circular motifs.
                sky("#C5CDD2", "#E3E0CE")
                polygon("#8A9BA0", 0f, 269f, 131f, 77f, 265f, 269f, 320f, 233f, 320f, 320f, 0f, 320f)
                polygon("#EFF0DD", 99f, 124f, 131f, 77f, 167f, 128f, 139f, 113f, 123f, 128f)
                polygon("#526E78", 74f, 320f, 232f, 141f, 320f, 254f, 320f, 320f)
                polygon("#314F5C", 0f, 287f, 84f, 233f, 179f, 320f, 0f, 320f)
            }
        }
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
        return output.toByteArray()
    }
}
