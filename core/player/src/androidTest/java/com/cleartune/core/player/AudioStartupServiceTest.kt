package com.cleartune.core.player

import android.content.ComponentName
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import com.cleartune.core.datastore.AppPreferences
import com.cleartune.core.datastore.EqualizerPreset
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Runs the real service, persisted settings and MediaSession, not a hand-opened gate. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class AudioStartupServiceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun savedEqWaitsForActualTrackAndDoesNotConsumeTheBeginning() {
        EqualizerPreset.entries.forEach { preset ->
            fixture(enabled = true, preset = preset) { f ->
                var initializedWhileMutedAndPaused = false
                val audioManager = instrumentation.targetContext.getSystemService(AudioManager::class.java)
                val systemVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                main {
                    assertEquals(Player.STATE_IDLE, f.player.playbackState)
                    assertEquals(0f, f.player.volume, 0f)
                    assertFalse(f.eq.isAttached)
                    assertFalse(f.gate.isReady)
                    f.player.addAnalyticsListener(object : AnalyticsListener {
                        override fun onAudioTrackInitialized(time: AnalyticsListener.EventTime, config: AudioSink.AudioTrackConfig) {
                            initializedWhileMutedAndPaused = f.player.volume == 0f &&
                                !f.player.playWhenReady && !f.gate.isReady
                        }
                    })
                    f.controller.setMediaItem(f.item)
                    f.controller.prepare()
                }
                await { f.gate.isReady }
                main {
                    assertTrue(initializedWhileMutedAndPaused)
                    assertEquals("Only non-flat curves should change the spectrum",
                        preset != EqualizerPreset.BALANCED, f.eq.isEqualizing)
                    assertFalse(f.player.playWhenReady)
                    assertEquals(0L, f.player.currentPosition)
                    val expectedGain = when (preset) {
                        EqualizerPreset.CUSTOM -> 0.5011872f
                        EqualizerPreset.BALANCED -> 1f
                        EqualizerPreset.NIGHT_SOFT -> 0.8912509f
                        else -> 0.7079458f
                    }
                    assertEquals(expectedGain,
                        f.player.volume, 0.001f)
                    f.controller.play()
                }
                await { f.player.isPlaying && f.player.currentPosition >= 200 }
                assertEquals(systemVolume, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
            }
        }
    }

    @Test fun immediatePlayAndEqDisabledBothStartThroughRealService() {
        listOf(false, true).forEach { enabled ->
            fixture(enabled = enabled) { f ->
                main { f.controller.setMediaItem(f.item); f.controller.play() }
                await { f.player.isPlaying && f.player.currentPosition >= 100 }
                main { assertTrue(f.eq.isAttached); assertEquals(enabled, f.eq.isEqualizing); assertNull(f.player.playerError) }
            }
        }
    }

    @Test fun pauseDuringStartupCancelsAutoplayButAllowsLaterPlay() = fixture { f ->
        main { f.controller.setMediaItem(f.item); f.controller.play(); f.controller.pause() }
        await { f.gate.isReady }
        main {
            assertFalse(f.player.playWhenReady)
            assertEquals(0L, f.player.currentPosition)
            f.controller.play()
        }
        await { f.player.isPlaying && f.player.currentPosition >= 100 }
    }

    @Test fun stopDuringStartupDoesNotAutoplayAndCanRetry() = fixture { f ->
        main { f.controller.setMediaItem(f.item); f.controller.play(); f.controller.stop() }
        await { f.player.mediaItemCount == 1 && f.player.playbackState == Player.STATE_IDLE }
        main {
            assertFalse(f.player.playWhenReady)
            assertEquals(0L, f.player.currentPosition)
            f.controller.play()
        }
        await { f.player.isPlaying && f.player.currentPosition >= 100 }
    }

    @Test fun radioFirstPlaybackAlsoPassesTheStartupGate() = fixture { f ->
        val radio = f.item.buildUpon().setMediaMetadata(MediaMetadata.Builder().setExtras(Bundle().apply {
            putBoolean("com.cleartune.song.is_radio", true)
        }).build()).build()
        main { f.controller.setMediaItem(radio); f.controller.play() }
        await { f.player.isPlaying && f.player.currentPosition >= 100 }
        main { assertTrue(f.eq.isAttached); assertNull(f.player.playerError) }
    }

    @Test fun failedSourceBeforeTrackCreationCanBeReplacedAndPlayed() = fixture { f ->
        main {
            f.controller.setMediaItem(MediaItem.fromUri("file:///missing-startup-regression.wav"))
            f.controller.play()
        }
        await { f.player.playerError != null }
        main {
            assertFalse(f.gate.isReady)
            assertFalse(f.eq.isAttached)
            f.controller.setMediaItem(f.item)
            f.controller.play()
        }
        await { f.player.isPlaying && f.player.currentPosition >= 100 }
    }

    private fun fixture(
        enabled: Boolean = true,
        preset: EqualizerPreset = EqualizerPreset.CUSTOM,
        block: (Fixture) -> Unit,
    ) {
        val context = instrumentation.targetContext
        val preferences = AppPreferences(context)
        val original = runBlocking { preferences.settings.first().equalizer }
        val audio = File.createTempFile("service-startup-", ".wav", context.cacheDir)
        // Nonzero PCM from the first frame: startup protection must not rely on a silent source.
        val frames = 44_100 * 3
        audio.writeBytes(ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + frames * 2); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(44_100); putInt(88_200)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(frames * 2)
            repeat(frames) { putShort((sin(2 * PI * 440 * it / 44_100 + PI / 2) * 2000).toInt().toShort()) }
        }.array())
        var future: ListenableFuture<MediaController>? = null
        try {
            runBlocking {
                preferences.setEqualizerCustomLevels(listOf(6, 6, 0, -3, -6))
                preferences.setEqualizerPreset(preset)
                preferences.setEqualizerEnabled(enabled)
            }
            main {
                future = MediaController.Builder(context, SessionToken(context,
                    ComponentName(context, PlaybackService::class.java))).buildAsync()
            }
            val controller = future!!.get(10, TimeUnit.SECONDS)
            val service = PlaybackService::class.java.getDeclaredField("activeService")
                .apply { isAccessible = true }.get(null) as PlaybackService
            val f = Fixture(controller, service, MediaItem.fromUri(audio.toURI().toString()))
            await { field(service, "audioSettingsLoaded") == true }
            // Let any incorrectly eager EQ job finish too: the old implementation
            // must fail deterministically even when device native creation is slow.
            await { (field(service, "equalizerTransitionJob") as Job?)?.isActive != true }
            block(f)
        } finally {
            main {
                future?.let { MediaController.releaseFuture(it) }
                context.stopService(Intent(context, PlaybackService::class.java))
            }
            await {
                PlaybackService::class.java.getDeclaredField("activeService")
                    .apply { isAccessible = true }.get(null) == null
            }
            runBlocking {
                preferences.setEqualizerCustomLevels(original.customLevelsDb)
                preferences.setEqualizerPreset(original.preset)
                preferences.setEqualizerEnabled(original.enabled)
            }
            audio.delete()
        }
    }

    private class Fixture(val controller: MediaController, val service: PlaybackService, val item: MediaItem) {
        val player get() = field(service, "player") as ExoPlayer
        val eq get() = field(service, "equalizerController") as EqualizerController
        val gate get() = field(service, "audioStartupGate") as AudioStartupGate
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            var done = false
            main { done = condition() }
            if (done) return
            Thread.sleep(10)
        }
        fail("Real service startup did not reach the expected state")
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    companion object {
        private fun field(service: PlaybackService, name: String): Any? =
            PlaybackService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)
    }
}
