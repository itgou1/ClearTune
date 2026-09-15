package com.cleartune.app.download

import android.content.Context
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.common.util.concurrent.ListenableFuture
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.suspendCancellableCoroutine

interface DownloadScheduler {
    fun observe(): Flow<List<WorkInfo>>
    suspend fun enqueue(request: OneTimeWorkRequest)
    suspend fun cancel(id: UUID)
    suspend fun update(request: OneTimeWorkRequest)
}

class WorkDownloadScheduler(context: Context) : DownloadScheduler {
    private val manager = WorkManager.getInstance(context)
    override fun observe() = manager.getWorkInfosByTagFlow(DownloadWorker::class.java.name)
    override suspend fun enqueue(request: OneTimeWorkRequest) { manager.enqueue(request).result.awaitDownloadResult() }
    override suspend fun cancel(id: UUID) { manager.cancelWorkById(id).result.awaitDownloadResult() }
    override suspend fun update(request: OneTimeWorkRequest) { manager.updateWork(request).awaitDownloadResult() }
}

private suspend fun <T> ListenableFuture<T>.awaitDownloadResult(): T = suspendCancellableCoroutine { continuation ->
    addListener({
        try {
            val value = get()
            if (continuation.isActive) continuation.resume(value)
        } catch (error: Exception) {
            if (continuation.isActive) continuation.resumeWithException(
                if (error is ExecutionException) error.cause ?: error else error,
            )
        }
    }, Executor { it.run() })
    continuation.invokeOnCancellation { cancel(false) }
}
