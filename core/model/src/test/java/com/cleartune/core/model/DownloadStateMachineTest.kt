package com.cleartune.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStateMachineTest {
    @Test
    fun supportsPauseResumeRetryAndCompletion() {
        assertTrue(DownloadStateMachine.canTransition(DownloadState.QUEUED, DownloadState.DOWNLOADING))
        assertTrue(DownloadStateMachine.canTransition(DownloadState.DOWNLOADING, DownloadState.PAUSED))
        assertTrue(DownloadStateMachine.canTransition(DownloadState.PAUSED, DownloadState.QUEUED))
        assertTrue(DownloadStateMachine.canTransition(DownloadState.FAILED, DownloadState.QUEUED))
        assertTrue(DownloadStateMachine.canTransition(DownloadState.DOWNLOADING, DownloadState.COMPLETED))
        assertFalse(DownloadStateMachine.canTransition(DownloadState.COMPLETED, DownloadState.DOWNLOADING))
    }
}
