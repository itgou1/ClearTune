package com.cleartune.app.download

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.cleartune.core.database.ClearTuneDatabase
import com.cleartune.core.database.DownloadEntity
import com.cleartune.core.model.DownloadItem
import com.cleartune.core.model.DownloadState
import com.cleartune.core.model.Song
import com.cleartune.core.datastore.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first

@Singleton
class DownloadRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    database: ClearTuneDatabase,
    private val preferences: AppPreferences,
) {
    private val dao = database.downloadDao()
    private val workManager = WorkManager.getInstance(context)

    val downloads: Flow<List<DownloadItem>> = dao.observeAll().map { entities ->
        entities.map { entity ->
            DownloadItem(
                requestId = entity.requestId,
                songId = entity.songId,
                state = runCatching { DownloadState.valueOf(entity.state) }.getOrDefault(DownloadState.FAILED),
                bytesDownloaded = entity.bytesDownloaded,
                totalBytes = entity.totalBytes,
                localUri = entity.localUri,
                failureReason = entity.failureReason,
            )
        }
    }

    suspend fun enqueue(songs: List<Song>) {
        val settings = preferences.settings.first()
        songs.forEach { song ->
            val existing = dao.forSong(song.id)
            if (existing?.state == DownloadState.COMPLETED.name && valid(existing.localUri)) return@forEach
            existing?.requestId?.toUuid()?.let(workManager::cancelWorkById)
            val requestId = UUID.randomUUID()
            dao.upsert(
                DownloadEntity(
                    requestId = requestId.toString(),
                    songId = song.id,
                    state = DownloadState.QUEUED.name,
                    bytesDownloaded = existing?.bytesDownloaded ?: 0,
                    totalBytes = existing?.totalBytes,
                    localUri = null,
                    failureReason = null,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setId(requestId)
                .setInputData(Data.Builder().putString(DownloadWorker.KEY_SONG_ID, song.id).build())
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(
                            if (settings.wifiOnlyDownloads) NetworkType.UNMETERED else NetworkType.CONNECTED,
                        )
                        .build(),
                )
                .build()
            workManager.enqueue(request)
        }
    }

    suspend fun pause(item: DownloadItem) {
        item.requestId.toUuid()?.let(workManager::cancelWorkById)
        dao.upsert(item.toEntity(DownloadState.PAUSED))
    }

    suspend fun retry(item: DownloadItem, song: Song) = enqueue(listOf(song))

    suspend fun delete(item: DownloadItem) {
        item.requestId.toUuid()?.let(workManager::cancelWorkById)
        item.localUri?.let(Uri::parse)?.path?.let(::File)?.takeIf(File::exists)?.delete()
        dao.delete(item.requestId)
    }

    private fun DownloadItem.toEntity(newState: DownloadState) = DownloadEntity(
        requestId = requestId,
        songId = songId,
        state = newState.name,
        bytesDownloaded = bytesDownloaded,
        totalBytes = totalBytes,
        localUri = localUri,
        failureReason = failureReason,
        updatedAt = System.currentTimeMillis(),
    )

    private fun valid(uri: String?): Boolean = uri?.let(Uri::parse)?.path?.let(::File)
        ?.let { it.exists() && it.length() > 0 } == true

    private fun String.toUuid(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()
}
