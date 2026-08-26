package com.cleartune.core.player

import com.cleartune.core.model.PlaybackMode
import org.junit.Assert.assertEquals
import org.junit.Test

class QueuePlannerTest {
    @Test
    fun cyclesThroughFourChineseFacingModesInOrder() {
        var mode = PlaybackMode.SEQUENTIAL
        mode = QueuePlanner.nextMode(mode)
        assertEquals(PlaybackMode.REPEAT_ALL, mode)
        mode = QueuePlanner.nextMode(mode)
        assertEquals(PlaybackMode.REPEAT_ONE, mode)
        mode = QueuePlanner.nextMode(mode)
        assertEquals(PlaybackMode.SHUFFLE, mode)
        assertEquals(PlaybackMode.SEQUENTIAL, QueuePlanner.nextMode(mode))
    }

    @Test
    fun movesAndRemovesWithoutMutatingInput() {
        val input = listOf("a", "b", "c")
        assertEquals(listOf("b", "c", "a"), QueuePlanner.move(input, 0, 2))
        assertEquals(listOf("a", "c"), QueuePlanner.remove(input, 1))
        assertEquals(listOf("a", "b", "c"), input)
    }
}
