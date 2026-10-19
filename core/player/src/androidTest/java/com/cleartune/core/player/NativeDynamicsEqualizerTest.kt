package com.cleartune.core.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.audiofx.DynamicsProcessing
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class NativeDynamicsEqualizerTest {
    @Test fun nativeEqSupportsFloatTrackWithoutCompressionOrChangingSystemVolume() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audio = context.getSystemService(AudioManager::class.java)
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(48000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
            .setBufferSizeInBytes(48000).setTransferMode(AudioTrack.MODE_STREAM).build()
        try {
            track.setVolume(0f)
            val eq = NativeEqualizerFactory.create(track.audioSessionId)
            try {
                assertTrue("This device must exercise native DP, not legacy/software fallback", eq is NativeDynamicsEqualizer)
                val native = eq.javaClass.getDeclaredField("effect").apply { isAccessible = true }
                    .get(eq) as DynamicsProcessing
                val config = native.config
                repeat(native.channelCount) { channel ->
                    assertTrue(config.getPreEqByChannelIndex(channel).isInUse)
                    assertEquals(10, config.getPreEqByChannelIndex(channel).bandCount)
                    assertFalse(config.getMbcByChannelIndex(channel).isInUse)
                    assertFalse(config.getPostEqByChannelIndex(channel).isInUse)
                    assertFalse(config.getLimiterByChannelIndex(channel).isInUse)
                }
                listOf(-600, -400, -300, -200, -100, 0, 100, 200, 400, 600).forEachIndexed { band, gain ->
                    eq.setLevel(band, gain.toShort())
                    repeat(native.channelCount) { channel ->
                        assertEquals(gain / 100f, native.getPreEqBandByChannelIndex(channel, band).gain, 0.001f)
                    }
                }
                eq.enabled = true
                assertTrue(eq.enabled)
                assertEquals(AudioFormat.ENCODING_PCM_FLOAT, track.audioFormat)
                assertEquals(volume, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                eq.enabled = false
                assertFalse(eq.enabled)
            } finally { eq.release() }
        } finally { track.release() }
    }
}
