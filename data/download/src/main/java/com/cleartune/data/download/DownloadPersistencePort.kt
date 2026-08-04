package com.cleartune.data.download

import com.cleartune.core.model.DownloadId
import com.cleartune.core.model.DownloadSummary
import okhttp3.HttpUrl

data class DownloadCredentials(
    val username: String,
    val password: CharArray,
)

data class DownloadWork(
    val summary: DownloadSummary,
    val url: HttpUrl,
    val paths: DownloadPaths,
    val expectedBytes: Long? = null,
    val etag: String? = null,
    val credentials: DownloadCredentials? = null,
)

/** Database boundary implemented transactionally by the app assembly. */
interface DownloadPersistencePort {
    suspend fun loadWork(downloadId: DownloadId): DownloadWork?
    suspend fun markRunning(downloadId: DownloadId)
    suspend fun persistProgress(downloadId: DownloadId, downloadedBytes: Long, totalBytes: Long?)

    /** Atomically marks the record complete and publishes its DOWNLOADED_FILE location. */
    suspend fun publishDownloadedLocation(downloadId: DownloadId, bytes: Long, finalPath: String)

    suspend fun recordFailure(downloadId: DownloadId, code: String)
}
