package com.cleartune.data.webdav

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cleartune.core.model.SourceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface WebDavSyncWorkerHost {
    val webDavSyncRunner: WebDavSyncRunner
}

class WebDavSyncWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val sourceId = inputData.getString(INPUT_SOURCE_ID)?.takeIf(String::isNotBlank)
            ?.let(::SourceId) ?: return Result.failure()
        val runner = runnerFrom(applicationContext) ?: return Result.failure()
        setForeground(createForegroundInfo(sourceId))
        return when (runner.run(sourceId)) {
            WebDavWorkerOutcome.COMPLETED -> Result.success()
            WebDavWorkerOutcome.RETRY -> Result.retry()
            WebDavWorkerOutcome.FAILED -> Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val sourceId = inputData.getString(INPUT_SOURCE_ID)?.takeIf(String::isNotBlank)
            ?.let(::SourceId) ?: SourceId("unknown")
        return createForegroundInfo(sourceId)
    }

    private fun createForegroundInfo(sourceId: SourceId): ForegroundInfo {
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "WebDAV sync", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = Notification.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Syncing music source")
            .setOngoing(true)
            .build()
        return ForegroundInfo(notificationId(sourceId), notification)
    }

    companion object {
        const val INPUT_SOURCE_ID = "source_id"
        const val CHANNEL_ID = "webdav_sync"

        fun workName(sourceId: SourceId): String = "webdav-sync-${sourceId.value}"

        fun notificationId(sourceId: SourceId): Int = 0x57440000 or (sourceId.value.hashCode() and 0x0000ffff)

        fun runnerFrom(application: Any): WebDavSyncRunner? =
            (application as? WebDavSyncWorkerHost)?.webDavSyncRunner

        fun request(sourceId: SourceId) = OneTimeWorkRequestBuilder<WebDavSyncWorker>()
            .setInputData(Data.Builder().putString(INPUT_SOURCE_ID, sourceId.value).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(workName(sourceId))
            .build()
    }
}

class WorkManagerWebDavSyncScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context)

    fun enqueue(sourceId: SourceId) {
        workManager.enqueueUniqueWork(
            WebDavSyncWorker.workName(sourceId),
            ExistingWorkPolicy.REPLACE,
            WebDavSyncWorker.request(sourceId),
        )
    }

    suspend fun cancel(sourceId: SourceId) {
        withContext(Dispatchers.IO) {
            workManager.cancelUniqueWork(WebDavSyncWorker.workName(sourceId)).result.get()
        }
    }
}
