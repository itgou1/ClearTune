package com.cleartune.core.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EqualizerPresetTest {
    @Test
    fun `balanced preset remains neutral`() {
        assertEquals(
            listOf(0, 0, 0, 0, 0),
            EqualizerSettings(preset = EqualizerPreset.BALANCED).activeLevelsDb,
        )
    }

    @Test
    fun `listening presets emphasize their intended bands`() {
        val vocal = EqualizerSettings(preset = EqualizerPreset.CLEAR_VOCAL).activeLevelsDb
        val bass = EqualizerSettings(preset = EqualizerPreset.WARM_BASS).activeLevelsDb
        val treble = EqualizerSettings(preset = EqualizerPreset.AIRY_TREBLE).activeLevelsDb
        val night = EqualizerSettings(preset = EqualizerPreset.NIGHT_SOFT).activeLevelsDb

        assertEquals(3, vocal.indexOf(vocal.max()))
        assertEquals(0, bass.indexOf(bass.max()))
        assertEquals(bass.max(), bass[1])
        assertEquals(4, treble.indexOf(treble.max()))
        assertTrue(treble.zipWithNext().all { (lower, higher) -> lower <= higher })
        assertTrue(night.first() < night[2])
        assertTrue(night.last() < night[2])
        listOf(vocal, bass, treble, night).forEach { levels ->
            assertEquals(0, levels.max())
            assertTrue(levels.min() >= -4)
        }
    }
}
