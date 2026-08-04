package com.cleartune.data.download

import com.cleartune.core.model.DownloadId
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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
    private val progressQueueDepth: (Int) -> Unit = {},
    private val transferFactory: (DownloadCredentials?) -> DownloadTransferExecutor,
) : DownloadWorkerRunner {
    override suspend fun run(downloadId: DownloadId): WorkerOutcome {
        val work = persistence.loadWork(downloadId) ?: return WorkerOutcome.FAILED
        val credentials = work.credentials
        return try {
            persistence.markRunning(downloadId)
            val execution = transfer(work, transferFactory(credentials))
            when (val result = execution.result) {
                is DownloadTransferResult.Completed -> complete(downloadId, work, result, execution.persistedBytes)
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
    ): CompletedTransferExecution = coroutineScope {
        val progressSignal = Channel<Unit>(Channel.CONFLATED)
        val latestProgress = AtomicReference<Pair<Long, Long?>?>(null)
        val signalPending = AtomicBoolean(false)
        val highest = AtomicLong(work.summary.bytesDownloaded)
        val persisted = AtomicLong(work.summary.bytesDownloaded)
        val writer = launch {
            for (ignored in progressSignal) {
                signalPending.set(false)
                progressQueueDepth(0)
                while (true) {
                    val (downloaded, total) = latestProgress.getAndSet(null) ?: break
                    persistence.persistProgress(work.summary.id, downloaded, total)
                    persisted.set(downloaded)
                }
            }
        }
        val context = currentCoroutineContext()
        var result: DownloadTransferResult? = null
        try {
            result = withContext(Dispatchers.IO) {
                executor.execute(
                    DownloadTransferRequest(work.url, work.paths, work.expectedBytes, work.etag),
                    shouldContinue = { context.isActive },
                    onProgress = { downloaded, total ->
                        while (true) {
                            val previous = highest.get()
                            if (downloaded <= previous) break
                            if (highest.compareAndSet(previous, downloaded)) {
                                latestProgress.set(downloaded to total)
                                if (signalPending.compareAndSet(false, true)) {
                                    progressQueueDepth(1)
                                    progressSignal.trySend(Unit).getOrThrow()
                                }
                                break
                            }
                        }
                    },
                )
            }
        } finally {
            progressSignal.close()
            joinAll(writer)
        }
        CompletedTransferExecution(requireNotNull(result), persisted.get())
    }

    private data class CompletedTransferExecution(
        val result: DownloadTransferResult,
        val persistedBytes: Long,
    )

    private suspend fun complete(
        downloadId: DownloadId,
        work: DownloadWork,
        completed: DownloadTransferResult.Completed,
        persistedBytes: Long,
    ): WorkerOutcome {
        val finalFile = work.paths.finalFile
        val valid = finalFile.isFile &&
            finalFile.length() == completed.bytes &&
            (work.expectedBytes == null || work.expectedBytes == completed.bytes)
        if (!valid) {
            persistence.recordFailure(downloadId, "final_file_missing")
            return WorkerOutcome.FAILED
        }
        if (completed.bytes > persistedBytes) {
            persistence.persistProgress(downloadId, completed.bytes, work.expectedBytes ?: completed.bytes)
        }
        persistence.publishDownloadedLocation(downloadId, completed.bytes, finalFile.absolutePath)
        return WorkerOutcome.COMPLETED
    }
}
