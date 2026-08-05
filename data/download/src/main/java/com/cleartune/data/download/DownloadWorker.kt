package com.cleartune.data.download

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
import com.cleartune.core.model.DownloadId
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun interface DownloadWorkerRunner {
    suspend fun run(downloadId: DownloadId): WorkerOutcome
}

enum class WorkerOutcome { COMPLETED, RETRY, FAILED }

interface DownloadWorkerHost {
    val downloadWorkerRunner: DownloadWorkerRunner
}

fun interface DownloadNetworkPolicyProvider {
    fun wifiOnly(): Boolean
}

internal enum class DownloadWorkerExecutionResult { SUCCESS, RETRY, FAILURE }

internal object DownloadWorkerExecutor {
    suspend fun execute(
        rawDownloadId: String?,
        application: Any,
        prepareForeground: suspend (DownloadId) -> Unit,
    ): DownloadWorkerExecutionResult {
        val id = rawDownloadId?.takeIf(String::isNotBlank)?.let(::DownloadId)
            ?: return DownloadWorkerExecutionResult.FAILURE
        val runner = DownloadWorker.runnerFrom(application) ?: return DownloadWorkerExecutionResult.FAILURE
        prepareForeground(id)
        return when (runner.run(id)) {
            WorkerOutcome.COMPLETED -> DownloadWorkerExecutionResult.SUCCESS
            WorkerOutcome.RETRY -> DownloadWorkerExecutionResult.RETRY
            WorkerOutcome.FAILED -> DownloadWorkerExecutionResult.FAILURE
        }
    }
}

class DownloadWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        return when (DownloadWorkerExecutor.execute(
            inputData.getString(INPUT_DOWNLOAD_ID),
            applicationContext,
        ) { id -> setForeground(createForegroundInfo(id)) }) {
            DownloadWorkerExecutionResult.SUCCESS -> Result.success()
            DownloadWorkerExecutionResult.RETRY -> Result.retry()
            DownloadWorkerExecutionResult.FAILURE -> Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val id = inputData.getString(INPUT_DOWNLOAD_ID)?.takeIf(String::isNotBlank)
            ?.let(::DownloadId) ?: DownloadId("unknown")
        return createForegroundInfo(id)
    }

    private fun createForegroundInfo(id: DownloadId): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = Notification.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading music")
            .setOngoing(true)
            .build()
        return ForegroundInfo(notificationId(id), notification, foregroundServiceType)
    }

    companion object {
        const val INPUT_DOWNLOAD_ID = "download_id"
        const val CHANNEL_ID = "downloads"
        val foregroundServiceType: Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        fun workName(id: DownloadId): String = "download-${id.value}"

        fun notificationId(id: DownloadId): Int = id.value.hashCode() xor 0x444C0000

        fun runnerFrom(application: Any): DownloadWorkerRunner? =
            (application as? DownloadWorkerHost)?.downloadWorkerRunner

        fun request(id: DownloadId, wifiOnly: Boolean = true) = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(Data.Builder().putString(INPUT_DOWNLOAD_ID, id.value).build())
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(
                    if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
                ).build(),
            )
            .addTag(workName(id))
            .build()
    }
}

fun interface DownloadFileLocator {
    fun paths(id: DownloadId): DownloadPaths?
}

internal interface DownloadWorkManagerGateway {
    suspend fun enqueueUnique(name: String, policy: ExistingWorkPolicy, request: OneTimeWorkRequest)
    suspend fun cancelUnique(name: String)
}

private class AndroidDownloadWorkManagerGateway(
    private val workManager: WorkManager,
) : DownloadWorkManagerGateway {
    override suspend fun enqueueUnique(name: String, policy: ExistingWorkPolicy, request: OneTimeWorkRequest) {
        withContext(Dispatchers.IO) { workManager.enqueueUniqueWork(name, policy, request).result.get() }
    }

    override suspend fun cancelUnique(name: String) {
        withContext(Dispatchers.IO) { workManager.cancelUniqueWork(name).result.get() }
    }
}

class WorkManagerDownloadScheduler internal constructor(
    private val downloadRoot: File,
    private val fileLocator: DownloadFileLocator,
    private val workGateway: DownloadWorkManagerGateway,
    private val networkPolicy: DownloadNetworkPolicyProvider = DownloadNetworkPolicyProvider { true },
) : DownloadScheduler {
    constructor(
        context: Context,
        downloadRoot: File,
        fileLocator: DownloadFileLocator,
        networkPolicy: DownloadNetworkPolicyProvider = DownloadNetworkPolicyProvider { true },
    ) : this(
        downloadRoot,
        fileLocator,
        AndroidDownloadWorkManagerGateway(WorkManager.getInstance(context)),
        networkPolicy,
    )

    override val waitsForWifi: Boolean get() = networkPolicy.wifiOnly()

    override suspend fun enqueue(id: DownloadId) {
        workGateway.enqueueUnique(
            DownloadWorker.workName(id),
            ExistingWorkPolicy.REPLACE,
            DownloadWorker.request(id, waitsForWifi),
        )
    }

    override suspend fun stop(id: DownloadId) {
        workGateway.cancelUnique(DownloadWorker.workName(id))
    }

    override suspend fun deleteFiles(id: DownloadId) {
        val paths = fileLocator.paths(id) ?: return
        deleteContained(paths.partialFile)
        deleteContained(paths.finalFile)
    }

    private fun deleteContained(file: File) {
        val rootPath = downloadRoot.canonicalFile.toPath()
        val filePath = file.canonicalFile.toPath()
        require(filePath.startsWith(rootPath)) { "Download path escapes its root" }
        if (file.exists() && !file.delete()) throw java.io.IOException("Unable to delete download file")
    }
}
