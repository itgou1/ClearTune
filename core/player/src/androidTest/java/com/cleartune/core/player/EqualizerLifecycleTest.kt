package com.cleartune.core.player

import android.media.AudioManager
import android.media.audiofx.Equalizer
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import com.cleartune.core.datastore.EqualizerSettings
import com.cleartune.core.datastore.EqualizerPreset
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Native lifecycle checks; audible artifacts still need testing on affected hardware. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class EqualizerLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun playbackProbeAcrossEqualizerToggleAndTrackChanges() = fixture(enableInitially = false) { service, player ->
        val context = instrumentation.targetContext
        val files = listOf(48_000, 44_100, 32_000).map { rate ->
            File(context.cacheDir, "eq-probe-$rate.wav").apply { writeBytes(tone(rate)) }
        }
        try {
            main {
                player.addListener(field(service, "playerListener") as Player.Listener)
                player.setMediaItems(files.map { MediaItem.fromUri(it.toURI().toString()) })
                player.repeatMode = Player.REPEAT_MODE_ALL
                player.prepare()
                player.play()
            }
            awaitPlaying(player)
            probe(service, player, "cold-play")
            Thread.sleep(1500)
            repeat(3) { index ->
                main { player.seekToNextMediaItem() }
                awaitPlaying(player)
                probe(service, player, "cold-skip-$index")
                Thread.sleep(1500)
            }
            probe(service, player, "enable-request")
            main { apply(service, enabled = true) }
            awaitTransition(service)
            main { assertNotNull("Native equalizer must be supported for this probe", field(service, "equalizer")) }
            probe(service, player, "enabled")
            Thread.sleep(2000)
            probe(service, player, "disable-request")
            main { apply(service, enabled = false) }
            awaitTransition(service)
            probe(service, player, "disabled")
            Thread.sleep(2000)
            repeat(6) { index ->
                main { player.seekToNextMediaItem() }
                awaitPlaying(player)
                probe(service, player, "disabled-skip-$index")
                Thread.sleep(1500)
            }
            main {
                assertNull("Disabled equalizer must not remain attached after track changes", field(service, "equalizer"))
                assertEquals(1f, player.volume, 0.0001f)
                assertNull(player.playerError)
            }
        } finally {
            main { player.stop() }
            files.forEach { it.delete() }
        }
    }

    private fun probe(service: PlaybackService, player: ExoPlayer, event: String) = main {
        Log.i("EqualizerProbe", "${System.currentTimeMillis()} $event session=${player.audioSessionId} " +
            "attached=${field(service, "equalizer") != null} volume=${player.volume} position=${player.currentPosition}")
    }

    private fun awaitPlaying(player: ExoPlayer) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            var playing = false
            main { assertNull(player.playerError); playing = player.isPlaying }
            if (playing) return
            Thread.sleep(20)
        }
        fail("Probe audio did not start")
    }

    private fun tone(rate: Int): ByteArray {
        val frames = rate * 12
        return ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + frames * 2); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(frames * 2)
            repeat(frames) { frame ->
                val envelope = minOf(1.0, frame.toDouble() / (rate * 0.02), (frames - 1 - frame).toDouble() / (rate * 0.02))
                putShort((sin(2 * PI * 440 * frame / rate) * 8000 * envelope).toInt().toShort())
            }
        }.array()
    }

    @Test fun disablingReleasesEffectAndRepeatedDisabledUpdatesLeaveItDetached() = fixture { service, player ->
        main { apply(service, enabled = false) }
        awaitTransition(service)
        main {
            assertNull(field(service, "equalizer"))
            assertEquals(1f, player.volume, 0.0001f)
            repeat(3) { apply(service, enabled = false) }
            assertNull(field(service, "equalizer"))
            assertEquals(1f, player.volume, 0.0001f)
        }
    }

    @Test fun rapidOffOnOffCancelsOldTransitionAndRestoresVolume() = fixture { service, player ->
        main { apply(service, enabled = false) }
        Thread.sleep(25)
        main { apply(service, enabled = true) }
        Thread.sleep(25)
        main { apply(service, enabled = false) }
        awaitTransition(service)
        main {
            assertNull(field(service, "equalizer"))
            assertEquals(1f, player.volume, 0.0001f)
            apply(service, enabled = true)
        }
        awaitTransition(service)
        main {
            assertTrue((field(service, "equalizer") as Equalizer).enabled)
            assertTrue(player.volume > 0f)
        }
    }

    private fun fixture(enableInitially: Boolean = true, block: (PlaybackService, ExoPlayer) -> Unit) {
        lateinit var service: PlaybackService
        lateinit var player: ExoPlayer
        try {
            main {
                service = PlaybackService()
                val context = instrumentation.targetContext
                player = ExoPlayer.Builder(context).build()
                player.audioSessionId = context.getSystemService(AudioManager::class.java).generateAudioSessionId()
                setField(service, "player", player)
                if (enableInitially) apply(service, enabled = true, animate = false)
            }
            main {
                if (enableInitially) {
                    assumeTrue("Device must provide a controllable native equalizer", field(service, "equalizer") != null)
                }
            }
            block(service, player)
        } finally {
            main {
                (field(service, "playbackScope") as CoroutineScope).cancel()
                (field(service, "equalizer") as Equalizer?)?.release()
                player.release()
            }
        }
    }

    private fun awaitTransition(service: PlaybackService) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (System.nanoTime() < deadline) {
            var complete = false
            main { complete = (field(service, "equalizerTransitionJob") as Job?)?.isActive != true }
            if (complete) return
            Thread.sleep(10)
        }
        fail("Equalizer transition did not finish")
    }

    private fun apply(service: PlaybackService, enabled: Boolean, animate: Boolean = true) {
        setField(service, "equalizerSettings", EqualizerSettings(enabled = enabled, preset = EqualizerPreset.CUSTOM,
            customLevelsDb = listOf(6, 6, 0, -3, -6)))
        PlaybackService::class.java.getDeclaredMethod("applyEqualizer", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(service, animate)
    }

    private fun field(service: PlaybackService, name: String): Any? =
        PlaybackService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)

    private fun setField(service: PlaybackService, name: String, value: Any) =
        PlaybackService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
