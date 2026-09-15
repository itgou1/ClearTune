package com.cleartune.app.download

import com.cleartune.core.model.ServerCredentials
import com.cleartune.core.model.accountStorageKey

internal fun matchesDownloadSession(
    expectedAccount: String?,
    expectedSession: String?,
    credentials: ServerCredentials?,
    currentSession: String?,
): Boolean = expectedAccount != null && expectedSession != null && credentials != null &&
    expectedSession == currentSession &&
    expectedAccount == accountStorageKey(credentials.baseUrl, credentials.username)
