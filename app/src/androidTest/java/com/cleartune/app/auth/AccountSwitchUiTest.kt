package com.cleartune.app.auth

import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.ViewModelProvider
import com.cleartune.app.MainActivity
import com.cleartune.app.library.MusicViewModel
import com.cleartune.app.player.PlayerViewModel
import java.net.ServerSocket
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class AccountSwitchUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun logoutAndLoginCreateFreshAccountPages() {
        lateinit var auth: AuthViewModel
        ui.runOnIdle { auth = ViewModelProvider(ui.activity)[AuthViewModel::class.java] }
        ui.waitUntil(30_000) { auth.state.value !is AuthUiState.Restoring }
        // Never replace an existing user's login when running this test manually.
        assumeTrue(auth.state.value is AuthUiState.Login)
        if (Build.VERSION.SDK_INT >= 33) {
            val permissionCommand = InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("pm grant ${ui.activity.packageName} android.permission.POST_NOTIFICATIONS")
            ParcelFileDescriptor.AutoCloseInputStream(permissionCommand).use { it.readBytes() }
        }
        val server = ServerSocket(0)
        val responder = thread(isDaemon = true, name = "account-isolation-test-server") {
            while (!server.isClosed) {
                runCatching {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val reader = socket.getInputStream().bufferedReader()
                        val request = reader.readLine().orEmpty()
                        while (!reader.readLine().isNullOrEmpty()) { /* consume HTTP headers */ }
                        val title = if (request.contains("u=alice")) "AliceOnly" else "BobOnly"
                        val body = """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"test","albumList2":{"album":[]},"artists":{"index":[]},"playlists":{"playlist":[]},"searchResult3":{"song":[{"id":"same-id","title":"$title"}]},"starred2":{"song":[]},"genres":{"genre":[]},"musicFolders":{"musicFolder":[]},"playQueue":{"entry":[]}}}""".toByteArray()
                        val output = socket.getOutputStream()
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        output.write(body)
                        output.flush()
                    }
                }
            }
        }
        try {
            fun connect(username: String): MusicViewModel {
                ui.runOnIdle { auth.connect("http://127.0.0.1:${server.localPort}/", username, "test-password", true) }
                ui.waitUntil(15_000) { auth.state.value is AuthUiState.Connected }
                lateinit var music: MusicViewModel
                ui.runOnIdle {
                    val owner = ViewModelProvider(ui.activity)[AccountViewModels::class.java]
                    music = ViewModelProvider(owner.store, ui.activity.defaultViewModelProviderFactory,
                        ui.activity.defaultViewModelCreationExtras)[MusicViewModel::class.java]
                }
                return music
            }
            fun logout() {
                ui.onNodeWithText("我的").performClick()
                ui.onNodeWithContentDescription("设置").performClick()
                ui.onNodeWithText("退出登录").performScrollTo()
                    .performSemanticsAction(SemanticsActions.OnClick) { it() }
                ui.waitUntil(15_000) { auth.state.value is AuthUiState.Login }
            }
            val alice = connect("alice")
            ui.waitUntil(15_000) { alice.libraryState.value.songs.any { it.title == "AliceOnly" } }
            lateinit var alicePlayer: PlayerViewModel
            ui.runOnIdle {
                val owner = ViewModelProvider(ui.activity)[AccountViewModels::class.java]
                alicePlayer = ViewModelProvider(owner.store, ui.activity.defaultViewModelProviderFactory,
                    ui.activity.defaultViewModelCreationExtras)[PlayerViewModel::class.java]
                alicePlayer.play(alice.libraryState.value.songs)
            }
            ui.waitUntil(15_000) { alicePlayer.state.value.queue.any { it.title == "AliceOnly" } }
            logout()
            val bob = connect("bob")
            assertNotSame(alice, bob)
            ui.waitUntil(15_000) { bob.libraryState.value.songs.any { it.title == "BobOnly" } }
            assertFalse(bob.libraryState.value.songs.any { it.title == "AliceOnly" })
            ui.runOnIdle {
                val owner = ViewModelProvider(ui.activity)[AccountViewModels::class.java]
                val player = ViewModelProvider(owner.store, ui.activity.defaultViewModelProviderFactory,
                    ui.activity.defaultViewModelCreationExtras)[PlayerViewModel::class.java]
                assertNotSame(alicePlayer, player)
                assertTrue(player.state.value.queue.isEmpty())
            }
            logout()
        } finally {
            if (auth.state.value is AuthUiState.Connected) {
                ui.runOnIdle {
                    val owner = ViewModelProvider(ui.activity)[AccountViewModels::class.java]
                    val player = ViewModelProvider(owner.store, ui.activity.defaultViewModelProviderFactory,
                        ui.activity.defaultViewModelCreationExtras)[PlayerViewModel::class.java]
                    auth.logout {
                        try { player.endSession() } finally { owner.clearSession() }
                    }
                }
                ui.waitUntil(15_000) { auth.state.value is AuthUiState.Login }
            }
            server.close()
            responder.join(1_000)
        }
    }
}
