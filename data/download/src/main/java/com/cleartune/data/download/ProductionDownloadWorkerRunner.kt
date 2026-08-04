package com.cleartune.data.download

import com.cleartune.core.model.DownloadId
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun interface DownloadTransferExecutor {
    fun execute(
        request: DownloadTransferRequest,
        shouldContinue: () -> Boolean,
        onProgress: (downloaded: Long, total: Long?) -> Unit,
    ): DownloadTransferResult
}

fun DownloadTransfer.asExecutor(): DownloadTransferExecutor = DownloadTransferExecutor(::execute)

class ProductionDownloadWorkerRunner(
    private val persistence: DownloadPersistencePort,
    private val transferFactory: (DownloadCredentials?) -> DownloadTransferExecutor,
) : DownloadWorkerRunner {
    override suspend fun run(downloadId: DownloadId): WorkerOutcome {
        val work = persistence.loadWork(downloadId) ?: return WorkerOutcome.FAILED
        val credentials = work.credentials
        return try {
            persistence.markRunning(downloadId)
            val result = transfer(work, transferFactory(credentials))
            when (result) {
                is DownloadTransferResult.Completed -> complete(downloadId, work, result)
                is DownloadTransferResult.RetryableFailure -> {
                    persistence.recordFailure(downloadId, result.code)
                    WorkerOutcome.RETRY
                }
                is DownloadTransferResult.PermanentFailure -> {
                    persistence.recordFailure(downloadId, result.code)
                    WorkerOutcome.FAILED
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            runCatching { persistence.recordFailure(downloadId, "worker_failure") }
            WorkerOutcome.FAILED
        } finally {
            credentials?.password?.fill('\u0000')
        }
    }

    private suspend fun transfer(
        work: DownloadWork,
        executor: DownloadTransferExecutor,
    ): DownloadTransferResult = coroutineScope {
        val progress = Channel<Pair<Long, Long?>>(Channel.UNLIMITED)
        val highest = AtomicLong(work.summary.bytesDownloaded)
        val writer = launch {
            for ((downloaded, total) in progress) {
                persistence.persistProgress(work.summary.id, downloaded, total)
            }
        }
        val context = currentCoroutineContext()
        try {
            withContext(Dispatchers.IO) {
                executor.execute(
                    DownloadTransferRequest(work.url, work.paths, work.expectedBytes, work.etag),
                    shouldContinue = { context.isActive },
                    onProgress = { downloaded, total ->
                        while (true) {
                            val previous = highest.get()
                            if (downloaded <= previous) break
                            if (highest.compareAndSet(previous, downloaded)) {
                                progress.trySend(downloaded to total).getOrThrow()
                                break
                            }
                        }
                    },
                )
            }
        } finally {
            progress.close()
            joinAll(writer)
        }
    }

    private suspend fun complete(
        downloadId: DownloadId,
        work: DownloadWork,
        completed: DownloadTransferResult.Completed,
    ): WorkerOutcome {
        val finalFile = work.paths.finalFile
        val valid = finalFile.isFile &&
            finalFile.length() == completed.bytes &&
            (work.expectedBytes == null || work.expectedBytes == completed.bytes)
        if (!valid) {
            persistence.recordFailure(downloadId, "final_file_missing")
            return WorkerOutcome.FAILED
        }
        persistence.publishDownloadedLocation(downloadId, completed.bytes, finalFile.absolutePath)
        return WorkerOutcome.COMPLETED
    }
}
