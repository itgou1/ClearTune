package com.cleartune.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionComparatorTest {
    @Test
    fun comparesCommonReleaseTags() {
        assertTrue(VersionComparator.isNewer("v1.2.0", "1.1.9"))
        assertFalse(VersionComparator.isNewer("1.0.0", "1.0.0"))
        assertFalse(VersionComparator.isNewer("0.9.9", "1.0.0"))
        assertTrue(VersionComparator.isNewer("1.0.1-beta", "1.0.0"))
    }
}
