package com.cleartune.app.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckPolicyTest {
    @Test
    fun firstCheckIsAlwaysDue() {
        assertTrue(isAutomaticUpdateCheckDue(lastCheckEpochMs = 0L, nowEpochMs = 1_000L))
    }

    @Test
    fun repeatedCheckWithinOneDayIsSkipped() {
        val lastCheck = 1_000L
        assertFalse(isAutomaticUpdateCheckDue(lastCheck, lastCheck + 23L * 60L * 60L * 1_000L))
    }

    @Test
    fun checkAfterOneDayIsDue() {
        val lastCheck = 1_000L
        assertTrue(isAutomaticUpdateCheckDue(lastCheck, lastCheck + 24L * 60L * 60L * 1_000L))
    }

    @Test
    fun clockRollbackAllowsRecoveryCheck() {
        assertTrue(isAutomaticUpdateCheckDue(lastCheckEpochMs = 10_000L, nowEpochMs = 5_000L))
    }
}
