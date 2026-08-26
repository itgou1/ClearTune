package com.cleartune.app.download

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cleartune.core.database.DatabaseFactory
import com.cleartune.core.database.DownloadEntity
import com.cleartune.core.database.DownloadDao
import com.cleartune.core.datastore.CredentialsStore
import com.cleartune.core.model.DownloadState
import com.cleartune.core.network.OpenSubsonicApiFactory
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class DownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val songId = inputData.getString(KEY_SONG_ID) ?: return@withContext Result.failure()
        val requestId = id.toString()
        val database = DatabaseFactory.create(applicationContext)
        val downloadDao = database.downloadDao()
        val song = database.mediaDao().song(songId) ?: return@withContext Result.failure()
        val credentials = CredentialsStore(applicationContext).credentials.first()
            ?: return@withContext Result.failure()
        val folder = File(applicationContext.filesDir, "offline_music").apply { mkdirs() }
        val safeId = songId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val suffix = song.suffix?.takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) } ?: "audio"
        val target = File(folder, "$safeId.$suffix")
        val temporary = File(folder, "$safeId.$suffix.part")
        var connection: HttpURLConnection? = null
        try {
            val requiredBytes = (song.sizeBytes ?: 0) + MIN_FREE_BYTES
            if (StatFs(folder.path).availableBytes < requiredBytes) {
                update(
                    downloadDao,
                    requestId,
                    songId,
                    DownloadState.FAILED,
                    temporary.length(),
                    song.sizeBytes,
                    "存储空间不足",
                )
                return@withContext Result.failure()
            }
            update(downloadDao, requestId, songId, DownloadState.DOWNLOADING, temporary.length(), null)
            val remote = OpenSubsonicApiFactory().authorized(credentials)
            val existing = temporary.length()
            connection = (URL(remote.streamUrl(songId)).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
                connect()
            }
            val append = existing > 0 && connection.responseCode == HttpURLConnection.HTTP_PARTIAL
            val initial = if (append) existing else 0L
            val total = connection.contentLengthLong.takeIf { it > 0 }?.plus(initial)
            connection.inputStream.use { input ->
                FileOutputStream(temporary, append).buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = initial
                    while (true) {
                        if (isStopped) {
                            update(downloadDao, requestId, songId, DownloadState.PAUSED, downloaded, total)
                            return@withContext Result.failure()
                        }
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (downloaded % PROGRESS_STEP < read) {
                            update(downloadDao, requestId, songId, DownloadState.DOWNLOADING, downloaded, total)
                        }
                    }
                }
            }
            val finalSize = temporary.length()
            if (total != null && finalSize != total) error("文件大小校验失败")
            if (target.exists() && !target.delete()) error("无法替换旧文件")
            if (!temporary.renameTo(target)) error("无法完成文件写入")
            downloadDao.upsert(
                DownloadEntity(
                    requestId = requestId,
                    songId = songId,
                    state = DownloadState.COMPLETED.name,
                    bytesDownloaded = target.length(),
                    totalBytes = target.length(),
                    localUri = Uri.fromFile(target).toString(),
                    failureReason = null,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            Result.success()
        } catch (error: Exception) {
            val retry = runAttemptCount < 2 && !isStopped
            update(
                downloadDao,
                requestId,
                songId,
                if (retry) DownloadState.QUEUED else if (isStopped) DownloadState.PAUSED else DownloadState.FAILED,
                temporary.length(),
                null,
                if (retry || isStopped) null else "下载中断，请重试",
            )
            if (retry) Result.retry() else Result.failure()
        } finally {
            connection?.disconnect()
            database.close()
        }
    }

    private suspend fun update(
        dao: DownloadDao,
        requestId: String,
        songId: String,
        state: DownloadState,
        bytes: Long,
        total: Long?,
        reason: String? = null,
    ) {
        dao.upsert(
            DownloadEntity(
                requestId = requestId,
                songId = songId,
                state = state.name,
                bytesDownloaded = bytes,
                totalBytes = total,
                localUri = null,
                failureReason = reason,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    companion object {
        const val KEY_SONG_ID = "song_id"
        private const val PROGRESS_STEP = 512 * 1_024L
        private const val MIN_FREE_BYTES = 10 * 1_024 * 1_024L
    }
}
