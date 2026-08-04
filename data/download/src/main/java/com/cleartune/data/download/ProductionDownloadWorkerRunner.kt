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
        var generation: Long? = null
        return try {
            generation = persistence.beginWork(downloadId) ?: return WorkerOutcome.FAILED
            val execution = transfer(work, generation, transferFactory(credentials))
            when (val result = execution.result) {
                is DownloadTransferResult.Completed -> complete(downloadId, generation, work, result, execution.persistedBytes)
                is DownloadTransferResult.RetryableFailure -> {
                    persistence.recordFailure(downloadId, generation, result.code)
                    WorkerOutcome.RETRY
                }
                is DownloadTransferResult.PermanentFailure -> {
                    persistence.recordFailure(downloadId, generation, result.code)
                    WorkerOutcome.FAILED
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            generation?.let { captured ->
                runCatching { persistence.recordFailure(downloadId, captured, "worker_failure") }
            }
            WorkerOutcome.FAILED
        } finally {
            credentials?.password?.fill('\u0000')
        }
    }

    private suspend fun transfer(
        work: DownloadWork,
        generation: Long,
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
                    if (persistence.persistProgress(work.summary.id, generation, downloaded, total)) {
                        persisted.set(downloaded)
                    }
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
        generation: Long,
        work: DownloadWork,
        completed: DownloadTransferResult.Completed,
        persistedBytes: Long,
    ): WorkerOutcome {
        val finalFile = work.paths.finalFile
        val valid = finalFile.isFile &&
            finalFile.length() == completed.bytes &&
            (work.expectedBytes == null || work.expectedBytes == completed.bytes)
        if (!valid) {
            persistence.recordFailure(downloadId, generation, "final_file_missing")
            return WorkerOutcome.FAILED
        }
        if (completed.bytes > persistedBytes) {
            persistence.persistProgress(
                downloadId,
                generation,
                completed.bytes,
                work.expectedBytes ?: completed.bytes,
            )
        }
        return if (persistence.publishDownloadedLocation(
            downloadId,
            generation,
            completed.bytes,
            finalFile.absolutePath,
        )) WorkerOutcome.COMPLETED else WorkerOutcome.FAILED
    }
}
