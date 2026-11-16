package com.cleartune.app

import org.junit.Assert.*
import org.junit.Test

class CacheCapacityTest {
    @Test fun exactCustomUnitsAndLimits() {
        assertEquals(1536, parseCacheCapacity("1.5", true))
        assertEquals(512, parseCacheCapacity("0.5", true))
        assertEquals(32768, parseCacheCapacity("32", true))
        assertEquals(513, parseCacheCapacity("513", false))
        listOf("", "NaN", "-1", "0", "32.1", "0.5001").forEach { assertNull(parseCacheCapacity(it, true)) }
        assertNull(parseCacheCapacity("512.5", false))
    }
    @Test fun gigabytesDoNotOverflowOrTruncate() {
        assertTrue(storageBytes(34_359_738_368L).contains("32"))
        assertTrue(storageBytes(1_610_612_736L).contains("1"))
    }
}
