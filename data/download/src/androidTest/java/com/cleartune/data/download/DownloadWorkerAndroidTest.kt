package com.cleartune.data.download

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadWorkerAndroidTest {
    @Test
    fun doWork_usesApplicationHost_andMapsOutcomes() = runBlocking {
        for ((outcome, expected) in listOf(
            WorkerOutcome.COMPLETED to ListenableWorker.Result.success(),
            WorkerOutcome.RETRY to ListenableWorker.Result.retry(),
            WorkerOutcome.FAILED to ListenableWorker.Result.failure(),
        )) {
            val worker = worker(HostContext(outcome))
            assertEquals(expected, worker.doWork())
        }
    }

    @Test
    fun doWork_failsWhenApplicationHostIsMissing() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(ListenableWorker.Result.failure(), worker(context).doWork())
    }

    @Test(expected = CancellationException::class)
    fun doWork_rethrowsCancellation() = runBlocking {
        worker(HostContext(null)).doWork()
    }

    private fun worker(context: Context): DownloadWorker =
        TestListenableWorkerBuilder.from(context, DownloadWorker::class.java)
            .setInputData(Data.Builder().putString(DownloadWorker.INPUT_DOWNLOAD_ID, "download-1").build())
            .build()

    private class HostContext(private val outcome: WorkerOutcome?) : ContextWrapper(
        ApplicationProvider.getApplicationContext(),
    ), DownloadWorkerHost {
        override fun getApplicationContext(): Context = this
        override val downloadWorkerRunner = DownloadWorkerRunner {
            outcome ?: throw CancellationException("stop")
        }
    }
}
