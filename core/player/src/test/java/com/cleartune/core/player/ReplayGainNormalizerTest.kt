package com.cleartune.core.player

import com.cleartune.core.model.ReplayGain
import org.junit.Assert.assertEquals
import org.junit.Test

class ReplayGainNormalizerTest {
    @Test
    fun disabledOrMissingMetadataKeepsOriginalVolume() {
        assertEquals(
            1f,
            ReplayGainNormalizer.volume(
                replayGain = ReplayGain(trackGainDb = -8.0),
                enabled = false,
                preferAlbum = false,
                headroomDb = 0.0,
            ),
        )
        assertEquals(
            1f,
            ReplayGainNormalizer.volume(
                replayGain = null,
                enabled = true,
                preferAlbum = false,
                headroomDb = 0.0,
            ),
        )
    }

    @Test
    fun queueHeadroomPreservesRelativeTrackGainWithoutDigitalBoost() {
        val quiet = ReplayGain(trackGainDb = 6.0, trackPeak = 0.5)
        val loud = ReplayGain(trackGainDb = -6.0, trackPeak = 1.0)
        val headroom = ReplayGainNormalizer.requiredHeadroomDb(listOf(quiet, loud), preferAlbum = false)

        assertEquals(6.0, headroom, 0.0001)
        assertEquals(1f, ReplayGainNormalizer.volume(quiet, true, false, headroom), 0.0001f)
        assertEquals(0.2512f, ReplayGainNormalizer.volume(loud, true, false, headroom), 0.0001f)
    }

    @Test
    fun albumModeUsesAlbumGainAndProtectsPeak() {
        val gain = ReplayGain(
            trackGainDb = -8.0,
            albumGainDb = 6.0,
            albumPeak = 2.0,
        )

        assertEquals(
            0.5f,
            ReplayGainNormalizer.volume(gain, true, preferAlbum = true, headroomDb = 0.0),
            0.0001f,
        )
    }

    @Test
    fun missingTrackGainUsesServerFallback() {
        val gain = ReplayGain(fallbackGainDb = -3.0)

        assertEquals(
            0.7079f,
            ReplayGainNormalizer.volume(gain, true, preferAlbum = false, headroomDb = 0.0),
            0.0001f,
        )
    }
}
