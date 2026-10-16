package com.cleartune.core.player

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class RadioMediaControlsTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun systemControllerCyclesRadioWithoutAnyAppScreen() = fixture(true, 3) { player, controller ->
        main {
            assertTrue(controller.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
            assertTrue(controller.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
            controller.seekToNext()
        }
        await { player.currentMediaItemIndex == 1 }
        main { controller.seekToNextMediaItem() }
        await { player.currentMediaItemIndex == 2 }
        main { controller.seekToNext() }
        await { player.currentMediaItemIndex == 0 }
        main { controller.seekToPrevious() }
        await { player.currentMediaItemIndex == 2 }
        main { player.stop(); player.playWhenReady = false; controller.seekToPreviousMediaItem() }
        await { player.currentMediaItemIndex == 1 && player.playWhenReady }
    }

    @Test fun singleRadioHasNoSkipAndSongsKeepTheirNonLoopingBoundary() {
        fixture(true, 1) { player, controller ->
            main {
                assertFalse(controller.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
                controller.seekToNextMediaItem()
                assertEquals(0, player.currentMediaItemIndex)
            }
        }
        fixture(false, 2) { player, controller ->
            main { controller.seekToNextMediaItem() }
            await { player.currentMediaItemIndex == 1 }
            main {
                assertFalse(player.hasNextMediaItem())
                assertFalse(player.playWhenReady)
                controller.seekToNextMediaItem()
            }
            main { assertEquals(1, player.currentMediaItemIndex) }
        }
    }

    private fun fixture(radio: Boolean, count: Int, block: (ExoPlayer, MediaController) -> Unit) {
        lateinit var player: ExoPlayer
        lateinit var session: MediaSession
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
        main {
            player = ExoPlayer.Builder(instrumentation.targetContext).build()
            player.volume = 0f
            val gate = AudioStartupGate { player.playWhenReady = it }.apply { open() }
            val wrapper = object : AudioStartupPlayer(player, gate) {
                override fun seekToPrevious() = seekToPreviousMediaItem()
                override fun getMaxSeekToPreviousPosition() = Long.MAX_VALUE
            }
            player.setMediaItems((0 until count).map { index ->
                MediaItem.Builder().setMediaId("station-$index")
                    .setUri("file:///nonexistent-radio-control-test.wav")
                    .setMediaMetadata(MediaMetadata.Builder().setExtras(Bundle().apply {
                        putBoolean("com.cleartune.song.is_radio", radio)
                    }).build()).build()
            })
            player.repeatMode = if (radio && count > 1) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
            session = MediaSession.Builder(instrumentation.targetContext, wrapper).build()
            future = MediaController.Builder(instrumentation.targetContext, session.token).buildAsync()
        }
        val controller = future.get(10, TimeUnit.SECONDS)
        try { block(player, controller) }
        finally { main { controller.release(); session.release(); player.release() } }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            var satisfied = false
            main { satisfied = condition() }
            if (satisfied) return
            Thread.sleep(20)
        }
        fail("System media command did not reach the player")
    }
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
