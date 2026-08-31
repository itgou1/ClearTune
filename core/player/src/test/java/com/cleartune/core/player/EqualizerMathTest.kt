package com.cleartune.core.player

import org.junit.Assert.assertEquals
import org.junit.Test

class EqualizerMathTest {
    @Test
    fun `headroom compensates the largest positive boost`() {
        assertEquals(0.631f, EqualizerMath.headroomMultiplier(listOf(4, 3, 0, -1, -2)), 0.001f)
    }

    @Test
    fun `flat and cut-only curves do not reduce output`() {
        assertEquals(1f, EqualizerMath.headroomMultiplier(listOf(-2, -1, 0, -3, -2)), 0.0001f)
    }

    @Test
    fun `millibel conversion obeys both product and device ranges`() {
        assertEquals(600.toShort(), EqualizerMath.millibels(9f, -1_500..1_500))
        assertEquals(300.toShort(), EqualizerMath.millibels(4f, -300..300))
    }

    @Test
    fun `curve interpolation follows logarithmic frequency spacing`() {
        val frequencies = listOf(100, 1_000)
        val levels = listOf(0, -4)

        assertEquals(-2f, interpolatedEqualizerLevelDb(frequencies, levels, 316), 0.01f)
        assertEquals(0f, interpolatedEqualizerLevelDb(frequencies, levels, 20), 0f)
        assertEquals(-4f, interpolatedEqualizerLevelDb(frequencies, levels, 20_000), 0f)
    }

    @Test
    fun `sampled curve preserves its anchor endpoints`() {
        val curve = sampledEqualizerCurveDb(
            anchorFrequenciesHz = listOf(60, 230, 910, 3_600, 14_000),
            anchorLevelsDb = listOf(0, -2, -4, -3, -2),
            pointCount = 65,
        )

        assertEquals(0f, curve.first(), 0f)
        assertEquals(-2f, curve.last(), 0f)
        assertEquals(65, curve.size)
    }
}
