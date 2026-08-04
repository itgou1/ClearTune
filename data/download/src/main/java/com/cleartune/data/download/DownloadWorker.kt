package com.cleartune.data.download

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

class DownloadWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(INPUT_DOWNLOAD_ID)?.takeIf(String::isNotBlank)
            ?: return Result.failure()
        val runner = runnerFrom(applicationContext) ?: return Result.failure()
        setForeground(createForegroundInfo(DownloadId(id)))
        return when (runner.run(DownloadId(id))) {
            WorkerOutcome.COMPLETED -> Result.success()
            WorkerOutcome.RETRY -> Result.retry()
            WorkerOutcome.FAILED -> Result.failure()
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
        return ForegroundInfo(notificationId(id), notification)
    }

    companion object {
        const val INPUT_DOWNLOAD_ID = "download_id"
        const val CHANNEL_ID = "downloads"
        fun workName(id: DownloadId): String = "download-${id.value}"

        fun notificationId(id: DownloadId): Int = 0x444C0000 or (id.value.hashCode() and 0x0000ffff)

        fun runnerFrom(application: Any): DownloadWorkerRunner? =
            (application as? DownloadWorkerHost)?.downloadWorkerRunner

        fun request(id: DownloadId) = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(Data.Builder().putString(INPUT_DOWNLOAD_ID, id.value).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(workName(id))
            .build()
    }
}

fun interface DownloadFileLocator {
    fun paths(id: DownloadId): DownloadPaths?
}

class WorkManagerDownloadScheduler(
    context: Context,
    private val downloadRoot: File,
    private val fileLocator: DownloadFileLocator,
) : DownloadScheduler {
    private val workManager = WorkManager.getInstance(context)

    override suspend fun enqueue(id: DownloadId) {
        withContext(Dispatchers.IO) {
            workManager.enqueueUniqueWork(
                DownloadWorker.workName(id),
                ExistingWorkPolicy.REPLACE,
                DownloadWorker.request(id),
            ).result.get()
        }
    }

    override suspend fun stop(id: DownloadId) {
        withContext(Dispatchers.IO) {
            workManager.cancelUniqueWork(DownloadWorker.workName(id)).result.get()
        }
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
