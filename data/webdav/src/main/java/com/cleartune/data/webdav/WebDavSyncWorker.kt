package com.cleartune.data.webdav

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cleartune.core.model.SourceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface WebDavSyncWorkerHost {
    val webDavSyncRunner: WebDavSyncRunner
}

internal enum class WebDavWorkerExecutionResult { SUCCESS, RETRY, FAILURE }

internal object WebDavWorkerExecutor {
    suspend fun execute(
        rawSourceId: String?,
        application: Any,
        prepareForeground: suspend (SourceId) -> Unit,
    ): WebDavWorkerExecutionResult {
        val sourceId = rawSourceId?.takeIf(String::isNotBlank)?.let(::SourceId)
            ?: return WebDavWorkerExecutionResult.FAILURE
        val runner = WebDavSyncWorker.runnerFrom(application) ?: return WebDavWorkerExecutionResult.FAILURE
        prepareForeground(sourceId)
        return when (runner.run(sourceId)) {
            WebDavWorkerOutcome.COMPLETED -> WebDavWorkerExecutionResult.SUCCESS
            WebDavWorkerOutcome.RETRY -> WebDavWorkerExecutionResult.RETRY
            WebDavWorkerOutcome.FAILED -> WebDavWorkerExecutionResult.FAILURE
        }
    }
}

class WebDavSyncWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        return when (WebDavWorkerExecutor.execute(
            inputData.getString(INPUT_SOURCE_ID),
            applicationContext,
        ) { sourceId -> setForeground(createForegroundInfo(sourceId)) }) {
            WebDavWorkerExecutionResult.SUCCESS -> Result.success()
            WebDavWorkerExecutionResult.RETRY -> Result.retry()
            WebDavWorkerExecutionResult.FAILURE -> Result.failure()
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
        return ForegroundInfo(notificationId(sourceId), notification, foregroundServiceType)
    }

    companion object {
        const val INPUT_SOURCE_ID = "source_id"
        const val CHANNEL_ID = "webdav_sync"
        val foregroundServiceType: Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC

        fun workName(sourceId: SourceId): String = "webdav-sync-${sourceId.value}"

        fun notificationId(sourceId: SourceId): Int = sourceId.value.hashCode() xor 0x57440000

        fun runnerFrom(application: Any): WebDavSyncRunner? =
            (application as? WebDavSyncWorkerHost)?.webDavSyncRunner

        fun request(sourceId: SourceId) = OneTimeWorkRequestBuilder<WebDavSyncWorker>()
            .setInputData(Data.Builder().putString(INPUT_SOURCE_ID, sourceId.value).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(workName(sourceId))
            .build()
    }
}

internal interface WebDavWorkManagerGateway {
    suspend fun enqueueUnique(name: String, policy: ExistingWorkPolicy, request: OneTimeWorkRequest)
    suspend fun cancelUnique(name: String)
}

private class AndroidWebDavWorkManagerGateway(
    private val workManager: WorkManager,
) : WebDavWorkManagerGateway {
    override suspend fun enqueueUnique(name: String, policy: ExistingWorkPolicy, request: OneTimeWorkRequest) {
        withContext(Dispatchers.IO) { workManager.enqueueUniqueWork(name, policy, request).result.get() }
    }

    override suspend fun cancelUnique(name: String) {
        withContext(Dispatchers.IO) { workManager.cancelUniqueWork(name).result.get() }
    }
}

class WorkManagerWebDavSyncScheduler internal constructor(
    private val workGateway: WebDavWorkManagerGateway,
) {
    constructor(context: Context) : this(AndroidWebDavWorkManagerGateway(WorkManager.getInstance(context)))

    suspend fun enqueue(sourceId: SourceId) {
        workGateway.enqueueUnique(
            WebDavSyncWorker.workName(sourceId),
            ExistingWorkPolicy.REPLACE,
            WebDavSyncWorker.request(sourceId),
        )
    }

    suspend fun cancel(sourceId: SourceId) {
        workGateway.cancelUnique(WebDavSyncWorker.workName(sourceId))
    }
}
