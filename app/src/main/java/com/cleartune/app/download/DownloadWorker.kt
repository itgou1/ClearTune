package com.cleartune.app.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.StatFs
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cleartune.core.database.ClearTuneDatabase
import com.cleartune.core.database.DatabaseFactory
import com.cleartune.core.database.DownloadDao
import com.cleartune.core.datastore.AppPreferences
import com.cleartune.core.datastore.CredentialsStore
import com.cleartune.core.network.OpenSubsonicApiFactory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class DownloadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val requestId = id.toString()
        var database: ClearTuneDatabase? = null
        var dao: DownloadDao? = null
        var temporary: File? = null
        var target: File? = null
        var connection: HttpURLConnection? = null
        var downloaded = 0L
        var total: Long? = null
        suspend fun update(state: String, reason: String? = null, uri: String? = null): Boolean =
            dao?.updateProgress(requestId, state, downloaded, total, uri, reason,
                System.currentTimeMillis()) == 1
        try {
            val songId = inputData.getString(KEY_SONG_ID) ?: throw DownloadProblem("下载任务缺少歌曲信息")
            val account = inputData.getString(KEY_ACCOUNT)?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
                ?: throw DownloadProblem("旧下载任务已失效，请重新下载")
            val token = inputData.getString(KEY_SESSION) ?: throw DownloadProblem("下载登录信息已失效")
            database = DatabaseFactory.create(applicationContext, account)
            dao = database.downloadDao()
            val row = dao.forRequest(requestId)
            if (row == null || row.songId != songId || row.state !in ACTIVE) return@withContext failure("任务已取消或替换")
            val store = CredentialsStore(applicationContext)
            val credentials = store.credentials.first()
            if (credentials == null || !matchesDownloadSession(account, token, credentials, store.sessionToken.first())) {
                throw DownloadProblem("登录状态已变化，请在当前账号重新下载")
            }
            val song = database.mediaDao().song(songId) ?: throw DownloadProblem("歌曲信息不存在，请先同步音乐库")
            val folder = File(applicationContext.filesDir, "accounts/$account/offline_music")
            if (!folder.isDirectory && !folder.mkdirs()) throw DownloadProblem("无法创建下载目录")
            val suffix = song.suffix?.takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) } ?: "audio"
            target = File(folder, "$requestId.$suffix")
            temporary = File(folder, "$requestId.part")
            val resumeId = inputData.getString(KEY_RESUME)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            val source = resumeId?.takeIf { it != id }?.let { File(folder, "$it.part") }
            if (!temporary.exists() && source?.isFile == true) source.copyTo(temporary)
            downloaded = temporary.length()
            total = song.sizeBytes
            if (StatFs(folder.path).availableBytes < ((total ?: 0) - downloaded).coerceAtLeast(0) + MIN_FREE_BYTES) {
                throw DownloadProblem("存储空间不足，请清理后重试")
            }
            val preferences = AppPreferences(applicationContext)
            suspend fun checkActive() {
                currentCoroutineContext().ensureActive()
                if (isStopped) throw CancellationException("Worker stopped")
                if (store.sessionToken.first() != token) throw DownloadProblem("登录状态已变化，请重新下载")
                if (dao.forRequest(requestId)?.state !in ACTIVE) throw TaskObsolete()
                if (preferences.settings.first().wifiOnlyDownloads && !isUnmetered()) throw WaitForNetwork()
            }
            checkActive()
            if (!update("DOWNLOADING", "正在连接服务器")) throw TaskObsolete()
            connection = (URL(OpenSubsonicApiFactory().authorized(credentials).downloadUrl(songId))
                .openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("Accept-Encoding", "identity")
                if (downloaded > 0) setRequestProperty("Range", "bytes=$downloaded-")
                connect()
            }
            val code = connection.responseCode
            if (code == 416 && downloaded > 0) {
                temporary.delete()
                downloaded = 0
                throw DownloadProblem("断点已失效，将重新下载", retryable = true)
            }
            if (code !in 200..299) throw DownloadProblem(when (code) {
                401, 403 -> "服务器未授权下载，请检查账号的下载权限"
                404 -> "服务器中没有找到这首歌曲"
                else -> "服务器返回错误（$code）"
            }, code == 408 || code == 429 || code >= 500)
            val type = connection.contentType.orEmpty().lowercase()
            if (type.contains("xml") || type.contains("json") || type.contains("html")) {
                throw DownloadProblem("服务器返回的不是音频文件，请检查下载权限")
            }
            val append = downloaded > 0 && code == 206
            if (code == 206) {
                val start = Regex("bytes (\\d+)-\\d+/[\\d*]+").matchEntire(
                    connection.getHeaderField("Content-Range").orEmpty())?.groupValues?.get(1)?.toLongOrNull()
                if (start != downloaded) {
                    temporary.delete()
                    downloaded = 0
                    throw DownloadProblem("服务器断点响应不匹配，将重新下载", retryable = true)
                }
            }
            if (!append) downloaded = 0
            total = connection.contentLengthLong.takeIf { it > 0 }?.plus(downloaded)
            if (!update("DOWNLOADING")) throw TaskObsolete()
            connection.inputStream.use { input ->
                FileOutputStream(temporary, append).buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var lastProgress = System.currentTimeMillis()
                    while (true) {
                        checkActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        val now = System.currentTimeMillis()
                        if (now - lastProgress >= 500 || downloaded % PROGRESS_STEP < count) {
                            if (StatFs(folder.path).availableBytes < MIN_FREE_BYTES) throw DownloadProblem("存储空间不足")
                            if (!update("DOWNLOADING")) throw TaskObsolete()
                            lastProgress = now
                        }
                    }
                }
            }
            checkActive()
            if (downloaded == 0L) throw DownloadProblem("服务器返回了空文件")
            if (total != null && temporary.length() != total) throw IOException("Incomplete response")
            if (!temporary.renameTo(target)) throw DownloadProblem("无法完成文件写入，请检查存储空间")
            total = downloaded
            if (!update("COMPLETED", uri = Uri.fromFile(target).toString())) {
                target.delete()
                throw TaskObsolete()
            }
            source?.delete()
            Result.success()
        } catch (_: TaskObsolete) {
            failure("任务已取消或替换")
        } catch (_: WaitForNetwork) {
            update("QUEUED", "等待非计费网络")
            Result.retry()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                runCatching { update("QUEUED", "下载已中断，等待系统重新调度") }
            }
            throw cancelled
        } catch (error: Exception) {
            val reason = when (error) {
                is DownloadProblem -> error.message ?: "下载失败"
                is SocketTimeoutException -> "连接服务器超时"
                is IOException -> "网络连接或文件读写中断"
                else -> "下载失败，请重试"
            }
            val retry = !isStopped && runAttemptCount < 2 &&
                (error is IOException || (error as? DownloadProblem)?.retryable == true)
            runCatching { update(if (retry) "QUEUED" else "FAILED",
                if (retry) "$reason，等待自动重试" else reason) }
            if (retry) Result.retry() else failure(reason)
        } finally {
            connection?.disconnect()
            // A cancelled/replaced worker must never recreate a deleted row or leave its final file behind.
            withContext(NonCancellable) {
                runCatching {
                    if (dao != null && dao.forRequest(requestId) == null) {
                        temporary?.delete()
                        target?.delete()
                    }
                }
            }
            database?.close()
        }
    }

    private fun isUnmetered(): Boolean {
        val manager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return manager.getNetworkCapabilities(manager.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    }

    private fun failure(reason: String): Result = Result.failure(workDataOf(KEY_ERROR to reason))

    companion object {
        const val KEY_SONG_ID = "song_id"
        const val KEY_ACCOUNT = "account_key"
        const val KEY_SESSION = "session_token"
        const val KEY_RESUME = "resume_request"
        const val KEY_ERROR = "download_error"
        private const val PROGRESS_STEP = 512 * 1_024L
        private const val MIN_FREE_BYTES = 10 * 1_024 * 1_024L
        private val ACTIVE = setOf("QUEUED", "DOWNLOADING")
    }
}

private class DownloadProblem(message: String, val retryable: Boolean = false) : Exception(message)
private class TaskObsolete : Exception()
private class WaitForNetwork : Exception()
