package com.cleartune.core.contracts

import com.cleartune.core.model.DownloadCommand
import com.cleartune.core.model.DownloadSummary
import kotlinx.coroutines.flow.Flow

interface DownloadRepository {
    fun observeDownloads(): Flow<List<DownloadSummary>>
    suspend fun dispatch(command: DownloadCommand)
}
