package com.cleartune.app.download

import com.cleartune.core.model.ServerCredentials
import com.cleartune.core.model.accountStorageKey
import org.junit.Assert.*
import org.junit.Test

class DownloadSessionPolicyTest {
    private val alice = ServerCredentials("https://music.example/", "alice", "secret")
    private val account = accountStorageKey(alice.baseUrl, alice.username)

    @Test fun onlyTheOriginalLoginCanRunAQueuedDownload() {
        assertTrue(matchesDownloadSession(account, "session-a", alice, "session-a"))
        assertFalse(matchesDownloadSession(account, "session-a", alice.copy(username = "bob"), "session-a"))
        assertFalse(matchesDownloadSession(account, "session-a", alice.copy(baseUrl = "https://other.example/"), "session-a"))
        assertFalse(matchesDownloadSession(account, "session-a", alice, "session-new-login"))
        assertFalse(matchesDownloadSession(account, "session-a", null, null))
    }

    @Test fun legacyWorkersWithoutOwnershipAreRejected() {
        assertFalse(matchesDownloadSession(null, null, alice, "session-a"))
        assertFalse(matchesDownloadSession(account, null, alice, "session-a"))
    }
}
