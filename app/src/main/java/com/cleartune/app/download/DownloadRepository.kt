package com.cleartune.app.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.work.*
import com.cleartune.app.displayableArtworkId
import com.cleartune.app.artwork.ArtworkCache
import com.cleartune.app.auth.AccountSession
import com.cleartune.core.database.DownloadEntity
import com.cleartune.core.datastore.AppPreferences
import com.cleartune.core.model.DownloadItem
import com.cleartune.core.model.DownloadState
import com.cleartune.core.model.Song
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class DownloadEnqueueResult(val queuedCount: Int, val alreadyDownloadedCount: Int,
    val waitingForWifi: Boolean, val failedCount: Int = 0)

class DownloadRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val session: AccountSession,
    private val preferences: AppPreferences,
    private val scheduler: DownloadScheduler,
) {
    private val dao = session.database.downloadDao()
    private val operations = Mutex()
    private val sessionTag = "download-session:${session.token}"
    val downloads: Flow<List<DownloadItem>> = dao.observeAll().map { rows ->
        rows.map { row -> DownloadItem(row.requestId, row.songId,
            runCatching { DownloadState.valueOf(row.state) }.getOrDefault(DownloadState.FAILED),
            row.bytesDownloaded, row.totalBytes, row.localUri, row.failureReason) }
    }

    /** Observe the scheduler even while the downloads page is closed. */
    suspend fun monitor() = coroutineScope {
        launch {
            preferences.settings.map { it.wifiOnlyDownloads }.distinctUntilChanged()
                .onEach {
                    applyNetworkPolicy(it)
                    if (session.active) OfflineArtworkWorker.enqueue(context, session.accountKey, session.token,
                        it, replace = true)
                }
                .retryWhen { _, _ -> delay(2_000); true }
                .collect()
        }
        launch {
            val network = flow {
                while (currentCoroutineContext().isActive) { emit(networkState()); delay(2_000) }
            }
            combine(dao.observeAll(), scheduler.observe(), network,
                preferences.settings.map { it.wifiOnlyDownloads }.distinctUntilChanged()) { rows, work, net, wifi ->
                MonitorSnapshot(rows, work, net, wifi)
            }.onEach { reconcile(it) }.retryWhen { _, _ -> delay(2_000); true }.collect()
        }
    }

    suspend fun enqueue(songs: List<Song>): DownloadEnqueueResult = operations.withLock {
        if (!session.active) return@withLock DownloadEnqueueResult(0, 0, false)
        val wifiOnly = preferences.settings.first().wifiOnlyDownloads
        val waitingForWifi = wifiOnly && !networkState().unmetered
        var queued = 0
        var already = 0
        var failed = 0
        for (song in songs) {
            if (!session.active) break
            val existing = dao.forSong(song.id)
            if (existing?.state == "COMPLETED" && valid(existing.localUri)) { already++; continue }
            try {
                if (existing != null) {
                    pauseRecord(existing, "正在重新安排下载")
                    existing.requestId.toUuid()?.let { scheduler.cancel(it) }
                }
                val id = UUID.randomUUID()
                val row = DownloadEntity(id.toString(), song.id, "QUEUED", 0,
                    song.sizeBytes, null, "正在提交下载任务", System.currentTimeMillis())
                dao.upsert(row)
                try {
                    scheduler.enqueue(request(id, song.id, wifiOnly, existing?.requestId))
                    if (!session.active) { pauseRecord(row, "已退出账号"); scheduler.cancel(id) } else queued++
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) {
                    dao.updateProgress(row.requestId, "FAILED", 0, row.totalBytes, null,
                        "无法提交后台下载任务，请点击继续重试", System.currentTimeMillis())
                    failed++
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { failed++ }
        }
        DownloadEnqueueResult(queued, already, waitingForWifi && queued > 0, failed)
    }

    suspend fun pause(item: DownloadItem) = operations.withLock {
        val row = dao.forRequest(item.requestId) ?: return@withLock
        pauseRecord(row, "已暂停，可点击继续下载")
        row.requestId.toUuid()?.let { scheduler.cancel(it) }
    }

    suspend fun retry(item: DownloadItem, song: Song): DownloadEnqueueResult {
        if (dao.forRequest(item.requestId)?.songId != song.id) return DownloadEnqueueResult(0, 0, false)
        return enqueue(listOf(song))
    }

    suspend fun delete(item: DownloadItem) = operations.withLock {
        val row = dao.forRequest(item.requestId) ?: return@withLock
        pauseRecord(row, "正在取消下载")
        row.requestId.toUuid()?.let { scheduler.cancel(it) }
        dao.delete(row.requestId)
        withContext(Dispatchers.IO) {
            row.localUri?.let(Uri::parse)?.path?.let(::File)?.delete()
            row.requestId.toUuid()?.let {
                File(context.filesDir, "accounts/${session.accountKey}/offline_music/$it.part").delete()
            }
        }
        ArtworkCache.pruneOffline(context, session.accountKey) {
            dao.all().mapNotNull { session.database.mediaDao().song(it.songId)?.coverArtId.displayableArtworkId() }.toSet()
        }
    }

    private suspend fun pauseRecord(row: DownloadEntity, reason: String) {
        dao.updateProgress(row.requestId, "PAUSED", row.bytesDownloaded, row.totalBytes,
            row.localUri, reason, System.currentTimeMillis())
    }

    internal suspend fun applyNetworkPolicy(wifiOnly: Boolean) = operations.withLock {
        if (!session.active) return@withLock
        val work = scheduler.observe().first().associateBy { it.id.toString() }
        for (row in dao.all().filter { it.state in ACTIVE }) {
            val info = work[row.requestId] ?: continue
            if (info.state.isFinished) continue
            try {
                if (sessionTag !in info.tags) {
                    pauseRecord(row, "旧任务需要重新安排，请点击继续")
                    scheduler.cancel(info.id)
                } else scheduler.update(request(info.id, row.songId, wifiOnly,
                    info.tags.firstOrNull { it.startsWith("download-resume:") }?.removePrefix("download-resume:")))
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { pauseRecord(row, "网络限制更新失败，请点击继续重试") }
        }
    }

    private suspend fun reconcile(snapshot: MonitorSnapshot) = operations.withLock {
        if (!session.active) return@withLock
        val work = snapshot.work.associateBy { it.id.toString() }
        for (candidate in snapshot.rows.filter { it.state in ACTIVE }) {
            // A worker may have completed after either observer snapshot was emitted.
            val row = dao.forRequest(candidate.requestId) ?: continue
            val info = work[row.requestId]
            val now = System.currentTimeMillis()
            if (row.state == "DOWNLOADING" && info?.state == WorkInfo.State.ENQUEUED && now - row.updatedAt < 2_000) continue
            val status = downloadStatus(row, info?.state, info?.runAttemptCount ?: 0,
                info?.outputData?.getString(DownloadWorker.KEY_ERROR), snapshot.network.connected,
                snapshot.network.unmetered, snapshot.wifiOnly, now) ?: continue
            dao.reconcile(row.requestId, row.state, row.updatedAt, status.state, status.reason, now)
        }
    }

    private fun request(id: UUID, songId: String, wifiOnly: Boolean, resumeFrom: String? = null): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<DownloadWorker>().setId(id).addTag(sessionTag)
            .apply { resumeFrom?.let { addTag("download-resume:$it") } }
            .setInputData(Data.Builder().putString(DownloadWorker.KEY_SONG_ID, songId)
                .putString(DownloadWorker.KEY_ACCOUNT, session.accountKey)
                .putString(DownloadWorker.KEY_SESSION, session.token)
                .putString(DownloadWorker.KEY_RESUME, resumeFrom).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(
                if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build()

    private fun networkState(): DownloadNetwork {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
        return DownloadNetwork(capabilities != null,
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true)
    }
    private fun valid(uri: String?): Boolean = uri?.let(Uri::parse)?.path?.let(::File)
        ?.let { it.isFile && it.length() > 0 } == true
    private fun String.toUuid(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()
    private data class DownloadNetwork(val connected: Boolean, val unmetered: Boolean)
    private data class MonitorSnapshot(val rows: List<DownloadEntity>, val work: List<WorkInfo>,
        val network: DownloadNetwork, val wifiOnly: Boolean)
    private companion object { val ACTIVE = setOf("QUEUED", "DOWNLOADING") }
}
