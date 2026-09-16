package com.cleartune.app.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.*
import com.cleartune.app.artwork.ArtworkCache
import com.cleartune.app.displayableArtworkId
import com.cleartune.core.database.DatabaseFactory
import com.cleartune.core.datastore.AppPreferences
import com.cleartune.core.datastore.CredentialsStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Backfills existing downloads without downloading their audio again. */
class OfflineArtworkWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val account = inputData.getString(DownloadWorker.KEY_ACCOUNT)
            ?.takeIf { it.matches(Regex("[a-f0-9]{64}")) } ?: return@withContext Result.failure()
        val token = inputData.getString(DownloadWorker.KEY_SESSION) ?: return@withContext Result.failure()
        val store = CredentialsStore(applicationContext)
        val credentials = store.credentials.first() ?: return@withContext Result.success()
        if (!matchesDownloadSession(account, token, credentials, store.sessionToken.first())) return@withContext Result.success()
        val database = DatabaseFactory.create(applicationContext, account)
        try {
            val preferences = AppPreferences(applicationContext)
            suspend fun checkActive() {
                currentCoroutineContext().ensureActive()
                check(store.sessionToken.first() == token) { "Account changed" }
                if (preferences.settings.first().wifiOnlyDownloads) {
                    val network = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    check(network.getNetworkCapabilities(network.activeNetwork)
                        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true)
                }
            }
            var failed = false
            val songsByCover = database.downloadDao().all().filter { it.state == "COMPLETED" }
                .mapNotNull { row -> database.mediaDao().song(row.songId)?.coverArtId.displayableArtworkId()
                    ?.let { it to row.songId } }.groupBy({ it.first }, { it.second })
            for ((cover, songIds) in songsByCover) {
                try {
                    ArtworkCache.saveOffline(applicationContext, credentials, cover) {
                        checkActive()
                        check(songIds.any { database.downloadDao().forSong(it)?.state == "COMPLETED" })
                    }
                    songIds.forEach { database.downloadDao().clearArtworkWarning(it, ARTWORK_WARNING) }
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { failed = true }
                if (store.sessionToken.first() != token) return@withContext Result.success()
            }
            ArtworkCache.pruneOffline(applicationContext, account) {
                database.downloadDao().all().mapNotNull {
                    database.mediaDao().song(it.songId)?.coverArtId.displayableArtworkId()
                }.toSet()
            }
            if (failed && runAttemptCount < 3) Result.retry() else Result.success()
        } finally { database.close() }
    }

    companion object {
        const val ARTWORK_WARNING = "歌曲已下载，封面暂未保存，将在联网后补齐"

        fun enqueue(context: Context, account: String, token: String, wifiOnly: Boolean, replace: Boolean = false) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "offline-artwork:$account:$token", if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<OfflineArtworkWorker>()
                    .addTag("com.cleartune.app.download.DownloadWorker")
                    .setInputData(workDataOf(DownloadWorker.KEY_ACCOUNT to account, DownloadWorker.KEY_SESSION to token))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(
                        if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build(),
            )
        }
    }
}
