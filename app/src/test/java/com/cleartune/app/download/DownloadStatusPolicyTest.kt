package com.cleartune.app.download

import androidx.work.WorkInfo
import com.cleartune.core.database.DownloadEntity
import org.junit.Assert.*
import org.junit.Test

class DownloadStatusPolicyTest {
    private val row = DownloadEntity("request", "song", "QUEUED", 0, null, null, null, 0)
    private fun status(work: WorkInfo.State?, row: DownloadEntity = this.row,
        attempts: Int = 0, error: String? = null, connected: Boolean = true,
        unmetered: Boolean = true, now: Long = 60_000) =
        downloadStatus(row, work, attempts, error, connected, unmetered, true, now)

    @Test fun missingJobGetsGracePeriodThenActionableFailure() {
        assertNull(status(null, now = 5_000))
        assertEquals("FAILED", status(null)?.state)
    }
    @Test fun failureIncludesWorkerReason() {
        assertEquals(DownloadStatus("FAILED", "服务器未授权下载"),
            status(WorkInfo.State.FAILED, error = "服务器未授权下载"))
    }
    @Test fun meteredWifiAndDisconnectedHaveDistinctReasons() {
        assertTrue(status(WorkInfo.State.ENQUEUED, unmetered = false)!!.reason!!.contains("计费"))
        assertEquals("等待网络连接", status(WorkInfo.State.ENQUEUED, connected = false)?.reason)
    }
    @Test fun retryIsVisibleAndCancellationIsResumable() {
        assertTrue(status(WorkInfo.State.ENQUEUED, attempts = 1)!!.reason!!.contains("自动重试"))
        assertEquals("PAUSED", status(WorkInfo.State.CANCELLED)?.state)
    }
    @Test fun terminalRowsCannotBeOverwrittenByStaleSchedulerSnapshots() {
        listOf("PAUSED", "COMPLETED", "FAILED").forEach { state ->
            WorkInfo.State.entries.forEach { work -> assertNull(status(work, row.copy(state = state))) }
        }
    }
    @Test fun successWithoutCompletedFileRecordIsNotLeftWaiting() {
        assertEquals("FAILED", status(WorkInfo.State.SUCCEEDED)?.state)
    }
    @Test fun unchangedStateDoesNotTriggerAnotherDatabaseWrite() {
        val downloading = row.copy(state = "DOWNLOADING")
        assertNull(status(WorkInfo.State.RUNNING, downloading))
    }
}
