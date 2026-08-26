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
        assertEquals(600.toShort(), EqualizerMath.millibels(9, -1_500..1_500))
        assertEquals(300.toShort(), EqualizerMath.millibels(4, -300..300))
    }
}
