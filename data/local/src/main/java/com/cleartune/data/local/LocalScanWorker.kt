package com.cleartune.data.local

import android.content.Context
import android.content.pm.PackageManager
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters

object LocalScanRuntime {
    @Volatile
    var coordinator: LocalScanCoordinator? = null
}

class LocalScanWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val coordinator = LocalScanRuntime.coordinator ?: return Result.failure()
        val permission = AudioPermissionPolicy.requiredPermission(android.os.Build.VERSION.SDK_INT)
        val granted = applicationContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        return when (coordinator.scan(granted).outcome) {
            LocalScanOutcome.COMPLETED -> Result.success()
            LocalScanOutcome.PERMISSION_REQUIRED -> Result.success()
            LocalScanOutcome.FAILED -> Result.retry()
        }
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
