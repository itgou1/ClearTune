package com.cleartune.core.player

import android.media.AudioManager
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import com.cleartune.core.datastore.EqualizerPreset
import com.cleartune.core.datastore.EqualizerSettings
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class AudioStartupPlayerTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun firstPlaybackWaitsAtZeroUntilNativeEffectAndHeadroomAreReady() = fixture { player, wrapper, gate ->
        val errors = mutableListOf<Throwable>()
        val equalizer = EqualizerController(::SystemEqualizer, { player.volume = it }, { errors += it })
        try {
            var session = 0
            main {
                session = player.audioSessionId
                wrapper.play()
                assertFalse(player.playWhenReady)
            }
            await { player.playbackState == Player.STATE_READY }
            val initialization = equalizer.update(session,
                EqualizerSettings(true, EqualizerPreset.CUSTOM, listOf(6, 6, 0, -3, -6)), true)
            runBlocking { initialization.join() }
            assertTrue("Native effect initialization failed: $errors", errors.isEmpty())
            assertTrue(equalizer.isAttached)
            main {
                assertEquals(0L, player.currentPosition)
                assertFalse(player.isPlaying)
                assertTrue(player.volume < 0.6f)
                gate.open()
            }
            await { player.isPlaying && player.currentPosition > 100 }
            main { assertEquals(session, player.audioSessionId); assertNull(player.playerError) }
        } finally { runBlocking { equalizer.close().join() } }
    }

    @Test fun pauseAndStopCancelPendingCommandsBeforeInitializationCompletes() {
        listOf<(AudioStartupPlayer) -> Unit>({ it.pause() }, { it.stop() }, { it.clearMediaItems() }).forEach { cancel ->
            fixture { player, wrapper, gate ->
                main {
                    wrapper.setPlayWhenReady(true)
                    cancel(wrapper)
                    gate.open()
                    assertFalse(player.playWhenReady)
                    assertEquals(0L, player.currentPosition)
                }
            }
        }
    }

    @Test fun restoringWithoutPlayStaysPausedAndSubsequentPlayStartsNormally() = fixture { player, wrapper, gate ->
        main { gate.open(); assertFalse(player.playWhenReady); wrapper.play() }
        await { player.isPlaying }
        main { wrapper.pause(); assertFalse(player.playWhenReady); wrapper.play() }
        await { player.isPlaying }
    }

    private fun fixture(block: (ExoPlayer, AudioStartupPlayer, AudioStartupGate) -> Unit) {
        val context = instrumentation.targetContext
        val audio = File.createTempFile("startup-eq-", ".wav", context.cacheDir)
        val frames = 44100 * 3
        audio.writeBytes(ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + frames * 2); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(44100); putInt(88200)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(frames * 2)
        }.array())
        lateinit var player: ExoPlayer
        lateinit var gate: AudioStartupGate
        lateinit var wrapper: AudioStartupPlayer
        main {
            player = ExoPlayer.Builder(context).build()
            player.audioSessionId = context.getSystemService(AudioManager::class.java).generateAudioSessionId()
            gate = AudioStartupGate { player.playWhenReady = it }
            wrapper = AudioStartupPlayer(player, gate)
            wrapper.setMediaItem(MediaItem.fromUri(audio.toURI().toString()))
        }
        try { block(player, wrapper, gate) }
        finally { main { gate.close(); player.release() }; audio.delete() }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            var done = false
            main { done = condition() }
            if (done) return
            Thread.sleep(20)
        }
        fail("Playback condition timed out")
    }
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
