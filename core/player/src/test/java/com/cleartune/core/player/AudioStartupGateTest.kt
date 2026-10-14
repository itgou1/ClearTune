package com.cleartune.core.player

import org.junit.Assert.*
import org.junit.Test

class AudioStartupGateTest {
    @Test fun earlyPlayWaitsForEffectsWithoutStartingThePlaybackClock() {
        val output = mutableListOf<Boolean>()
        val gate = AudioStartupGate { output += it }
        repeat(3) { gate.request(true) }
        assertTrue(output.isEmpty())
        gate.open()
        gate.open()
        assertEquals(listOf(true), output)
    }

    @Test fun pauseOrStopDuringStartupCancelsPendingPlayback() {
        val output = mutableListOf<Boolean>()
        val gate = AudioStartupGate { output += it }
        gate.request(true)
        gate.request(false)
        gate.open()
        assertEquals(listOf(false), output)
        gate.request(true)
        assertEquals(listOf(false, true), output)
    }

    @Test fun restoredQueueDoesNotAutoplayAndReadyPlaybackHasNoExtraDelay() {
        val output = mutableListOf<Boolean>()
        val gate = AudioStartupGate { output += it }
        gate.open()
        assertTrue(output.isEmpty())
        gate.request(true)
        gate.request(false)
        gate.request(true)
        assertEquals(listOf(true, false, true), output)
    }

    @Test fun destroyedServiceCannotResumeFromALateInitialization() {
        val output = mutableListOf<Boolean>()
        val gate = AudioStartupGate { output += it }
        gate.request(true)
        gate.close()
        gate.open()
        gate.request(true)
        assertTrue(output.isEmpty())
    }
}
