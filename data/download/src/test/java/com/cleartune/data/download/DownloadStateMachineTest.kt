package com.cleartune.data.download

import com.cleartune.core.model.DownloadState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStateMachineTest {
    @Test
    fun `supports pause resume retry and completion paths`() {
        assertTrue(DownloadStateMachine.canTransition(DownloadState.QUEUED, DownloadState.RUNNING))
        assertTrue(DownloadStateMachine.canTransition(DownloadState.RUNNING, DownloadState.PAUSED))
        assertTrue(DownloadStateMachine.canTransition(DownloadState.PAUSED, DownloadState.QUEUED))
        assertTrue(DownloadStateMachine.canTransition(DownloadState.RUNNING, DownloadState.COMPLETED))
        assertTrue(DownloadStateMachine.canTransition(DownloadState.FAILED, DownloadState.QUEUED))
    }

    @Test
    fun `terminal and impossible transitions are rejected`() {
        assertFalse(DownloadStateMachine.canTransition(DownloadState.COMPLETED, DownloadState.RUNNING))
        assertFalse(DownloadStateMachine.canTransition(DownloadState.CANCELED, DownloadState.RUNNING))
        assertFalse(DownloadStateMachine.canTransition(DownloadState.PAUSED, DownloadState.COMPLETED))
        assertFalse(DownloadStateMachine.canTransition(DownloadState.RUNNING, DownloadState.QUEUED))
    }
}
