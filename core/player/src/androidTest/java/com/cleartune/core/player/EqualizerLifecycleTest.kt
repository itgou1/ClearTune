package com.cleartune.core.player

import android.media.AudioManager
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
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

    @Test fun flatAndOffKeepSameNativeHandleAndUnityGain() = fixture(enableInitially = false) { service, player ->
        fun nativeEffect() = EqualizerController::class.java.getDeclaredField("effect")
            .apply { isAccessible = true }.get(controller(service)) as EqualizerEffect
        val original = nativeEffect()
        repeat(3) {
            listOf(true, false).forEach { enabled ->
                main { apply(service, enabled, preset = EqualizerPreset.BALANCED) }
                awaitTransition(service)
                main {
                    assertSame(original, nativeEffect())
                    assertTrue(original.enabled)
                    assertFalse(controller(service).isEqualizing)
                    assertEquals(1f, player.volume, 0f)
                    original.centerFrequenciesHz.indices.forEach { band ->
                        assertEquals(0.toShort(), original.getLevel(band))
                    }
                }
            }
        }
    }

    @Test fun playbackContinuesAcrossEqualizerToggleAndTrackChanges() = fixture(enableInitially = false) { service, player ->
        val context = instrumentation.targetContext
        var lowestVolume = 1f
        val files = listOf(48_000, 44_100, 32_000).map { rate ->
            File(context.cacheDir, "eq-probe-$rate.wav").apply { writeBytes(tone(rate)) }
        }
        try {
            main {
                player.addListener(field(service, "playerListener") as Player.Listener)
                player.addListener(object : Player.Listener {
                    override fun onVolumeChanged(volume: Float) {
                        lowestVolume = minOf(lowestVolume, volume)
                    }
                })
                player.setMediaItems(files.map { MediaItem.fromUri(it.toURI().toString()) })
                player.repeatMode = Player.REPEAT_MODE_ALL
                player.prepare()
                player.play()
            }
            awaitPlaying(player)
            Thread.sleep(1500)
            repeat(3) { index ->
                main { player.seekToNextMediaItem() }
                awaitPlaying(player)
                Thread.sleep(1500)
            }
            main { apply(service, enabled = true) }
            awaitTransition(service)
            main { assertTrue("Native equalizer must be supported for this probe", controller(service).isAttached) }
            Thread.sleep(2000)
            main { apply(service, enabled = false) }
            awaitTransition(service)
            Thread.sleep(2000)
            repeat(6) { index ->
                main { player.seekToNextMediaItem() }
                awaitPlaying(player)
                Thread.sleep(1500)
            }
            main {
                assertTrue("Native chain must remain attached across toggles", controller(service).isAttached)
                assertFalse(controller(service).isEqualizing)
                assertEquals(1f, player.volume, 0.0001f)
                assertNull(player.playerError)
                assertTrue("EQ switching must not dip below its required 6 dB headroom", lowestVolume >= 0.501f)
            }
        } finally {
            main { player.stop() }
            files.forEach { it.delete() }
        }
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

    @Test fun disablingKeepsUnityEffectAndRepeatedDisabledUpdatesDoNotReattach() = fixture { service, player ->
        main { apply(service, enabled = false) }
        awaitTransition(service)
        main {
            assertTrue(controller(service).isAttached)
            assertFalse(controller(service).isEqualizing)
            assertEquals(1f, player.volume, 0.0001f)
            repeat(3) { apply(service, enabled = false) }
            assertTrue(controller(service).isAttached)
            assertFalse(controller(service).isEqualizing)
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
            assertTrue(controller(service).isAttached)
            assertFalse(controller(service).isEqualizing)
            assertEquals(1f, player.volume, 0.0001f)
            apply(service, enabled = true)
        }
        awaitTransition(service)
        main {
            assertTrue(controller(service).isAttached)
            assertTrue(player.volume > 0f)
        }
    }

    private fun fixture(enableInitially: Boolean = true, block: (PlaybackService, ExoPlayer) -> Unit) {
        lateinit var service: PlaybackService
        lateinit var player: ExoPlayer
        val audio = File.createTempFile("eq-lifecycle-", ".wav", instrumentation.targetContext.cacheDir)
        audio.writeBytes(tone(44_100))
        try {
            main {
                service = PlaybackService()
                val context = instrumentation.targetContext
                android.content.ContextWrapper::class.java
                    .getDeclaredMethod("attachBaseContext", android.content.Context::class.java)
                    .apply { isAccessible = true }.invoke(service, context)
                player = ExoPlayer.Builder(context).build()
                player.volume = 0f
                player.audioSessionId = context.getSystemService(AudioManager::class.java).generateAudioSessionId()
                setField(service, "player", player)
                setField(service, "audioSettingsLoaded", true)
                player.addAnalyticsListener(field(service, "audioStartupListener") as AnalyticsListener)
                apply(service, enabled = enableInitially, animate = false)
                player.setMediaItem(MediaItem.fromUri(audio.toURI().toString()))
                player.prepare()
            }
            val deadline = System.nanoTime() + 5_000_000_000L
            var ready = false
            while (!ready && System.nanoTime() < deadline) {
                main { ready = (field(service, "audioStartupGate") as AudioStartupGate).isReady }
                if (!ready) Thread.sleep(10)
            }
            assertTrue("Real AudioTrack/EQ startup did not finish", ready)
            awaitTransition(service)
            main {
                if (enableInitially) {
                    assumeTrue("Device must provide a controllable native equalizer", controller(service).isAttached)
                }
            }
            block(service, player)
        } finally {
            main {
                (field(service, "playbackScope") as CoroutineScope).cancel()
                controller(service).close()
                player.release()
            }
            audio.delete()
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

    private fun apply(service: PlaybackService, enabled: Boolean, animate: Boolean = true,
        preset: EqualizerPreset = EqualizerPreset.CUSTOM) {
        setField(service, "equalizerSettings", EqualizerSettings(enabled = enabled, preset = preset,
            customLevelsDb = listOf(6, 6, 0, -3, -6)))
        PlaybackService::class.java.getDeclaredMethod("applyEqualizer", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(service, animate)
    }

    private fun controller(service: PlaybackService) = field(service, "equalizerController") as EqualizerController

    private fun field(service: PlaybackService, name: String): Any? =
        PlaybackService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)

    private fun setField(service: PlaybackService, name: String, value: Any) =
        PlaybackService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
