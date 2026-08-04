package com.cleartune.data.local

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters

fun interface LocalScanWorkerRunner {
    suspend fun run(): LocalScanResult
}

internal enum class LocalWorkerDecision { SUCCESS, FAILURE, RETRY }

internal class LocalScanWorkExecutor(
    private val runner: LocalScanWorkerRunner?,
) {
    suspend fun execute(): LocalWorkerDecision {
        val provisionedRunner = runner ?: return LocalWorkerDecision.FAILURE
        return when (provisionedRunner.run().outcome) {
            LocalScanOutcome.COMPLETED,
            LocalScanOutcome.PERMISSION_REQUIRED,
            -> LocalWorkerDecision.SUCCESS
            LocalScanOutcome.TRANSIENT_FAILURE -> LocalWorkerDecision.RETRY
            LocalScanOutcome.FAILED -> LocalWorkerDecision.FAILURE
        }
    }
}

class LocalScanWorker @JvmOverloads constructor(
    appContext: Context,
    params: WorkerParameters,
    private val runner: LocalScanWorkerRunner? = null,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = when (LocalScanWorkExecutor(runner).execute()) {
        LocalWorkerDecision.SUCCESS -> Result.success()
        LocalWorkerDecision.FAILURE -> Result.failure()
        LocalWorkerDecision.RETRY -> Result.retry()
    }
}

class LocalScanWorkerFactory(
    private val runner: LocalScanWorkerRunner,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = if (workerClassName == LocalScanWorker::class.java.name) {
        LocalScanWorker(appContext, workerParameters, runner)
    } else {
        null
    }
}

class LocalScanScheduler(private val workManager: WorkManager) {
    fun enqueueAutomatic() = enqueue(ExistingWorkPolicy.KEEP)
    fun enqueueManualRefresh() = enqueue(ExistingWorkPolicy.REPLACE)

    private fun enqueue(policy: ExistingWorkPolicy) {
        workManager.enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            policy,
            OneTimeWorkRequestBuilder<LocalScanWorker>().build(),
        )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "local-library-scan"
    }
}
