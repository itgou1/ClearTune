package com.cleartune.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeMotionTest {
    @Test fun sectionsStartInOrderAndFinishWithinThreeHundredMilliseconds() {
        assertEquals(0f, homeEntranceFraction(0f, 0), 0f)
        assertTrue(homeEntranceFraction(0.1f, 0) > 0f)
        assertEquals(0f, homeEntranceFraction(0.1f, 1), 0f)
        assertEquals(0f, homeEntranceFraction(0.1f, 2), 0f)
        assertTrue(homeEntranceFraction(0.2f, 1) > 0f)
        assertEquals(0f, homeEntranceFraction(0.2f, 2), 0f)
        (0..2).forEach { assertEquals(1f, homeEntranceFraction(1f, it), 0f) }
    }

    @Test fun entranceNeverOvershootsOrReverses() {
        (0..2).forEach { section ->
            val fractions = (-10..110).map { homeEntranceFraction(it / 100f, section) }
            assertTrue(fractions.all { it in 0f..1f })
            assertTrue(fractions.zipWithNext().all { (before, after) -> after >= before })
        }
    }
}
