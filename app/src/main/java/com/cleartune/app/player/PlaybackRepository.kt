package com.cleartune.app.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import com.cleartune.app.displayableArtworkId
import com.cleartune.core.database.ClearTuneDatabase
import com.cleartune.core.database.PendingMutationEntity
import com.cleartune.core.database.PlayEventEntity
import com.cleartune.core.database.QueueItemEntity
import com.cleartune.core.database.toModel
import com.cleartune.core.database.toEntity
import com.cleartune.core.datastore.CredentialsStore
import com.cleartune.core.datastore.AppPreferences
import com.cleartune.core.datastore.MobileAudioQuality
import com.cleartune.core.model.PlayEventType
import com.cleartune.core.model.Song
import com.cleartune.core.network.LibraryRemoteDataSource
import com.cleartune.core.network.OpenSubsonicApiFactory
import com.cleartune.core.network.RemoteResult
import com.cleartune.core.player.PlayerUiState
import java.util.UUID
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first

data class RestoredQueue(
    val songs: List<Song>,
    val currentIndex: Int,
    val positionMs: Long,
)

data class PlaybackUrls(
    val streams: Map<String, String>,
    val artwork: Map<String, String>,
)

@Singleton
class PlaybackRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val credentialsStore: CredentialsStore,
    private val database: ClearTuneDatabase,
    private val apiFactory: OpenSubsonicApiFactory,
    private val preferences: AppPreferences,
) {
    private val queueDao = database.queueDao()
    private val mediaDao = database.mediaDao()
    private val activityDao = database.activityDao()

    suspend fun urls(songs: List<Song>): PlaybackUrls? {
        val remote = remote() ?: return null
        val cellular = (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
            .getNetworkCapabilities(
                (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetwork,
            )
            ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val settings = preferences.settings.first()
        val serverProfile = credentialsStore.profile.first()
        val streams = mutableMapOf<String, String>()
        songs.forEach { song ->
            val download = database.downloadDao().forSong(song.id)
            val localFile = download?.localUri?.let(Uri::parse)?.path?.let(::File)
            if (download?.state == com.cleartune.core.model.DownloadState.COMPLETED.name &&
                localFile?.let { it.exists() && it.length() > 0 } == true
            ) {
                streams[song.id] = download.localUri.orEmpty()
            } else {
                if (download?.state == com.cleartune.core.model.DownloadState.COMPLETED.name) {
                    database.downloadDao().upsert(
                        download.copy(
                            state = com.cleartune.core.model.DownloadState.FAILED.name,
                            failureReason = "本地文件已丢失或损坏",
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                }
                val mobileQuality = settings.mobileAudioQuality
                streams[song.id] = remote.streamUrl(
                    id = song.id,
                    maxBitRate = if (cellular) mobileQuality.maxBitRate else null,
                    format = if (
                        cellular &&
                        mobileQuality == MobileAudioQuality.ORIGINAL &&
                        serverProfile.supportsRawStreaming()
                    ) {
                        "raw"
                    } else {
                        null
                    },
                )
            }
        }
        return PlaybackUrls(
            streams = streams,
            artwork = songs.mapNotNull { song ->
                song.coverArtId.displayableArtworkId()?.let { song.id to remote.coverArtUrl(it, 768) }
            }.toMap(),
        )
    }

    suspend fun restoreQueue(): RestoredQueue? {
        val items = queueDao.queue()
        if (items.isEmpty()) return null
        val songs = items.mapNotNull { mediaDao.song(it.songId)?.toModel() }
        if (songs.isEmpty()) return null
        val current = items.indexOfFirst(QueueItemEntity::isCurrent).takeIf { it >= 0 } ?: 0
        return RestoredQueue(
            songs = songs,
            currentIndex = current.coerceIn(songs.indices),
            positionMs = items.getOrNull(current)?.playbackPositionMs ?: 0,
        )
    }

    suspend fun newerServerQueue(): RestoredQueue? {
        val localChangedAt = queueDao.queue().maxOfOrNull(QueueItemEntity::updatedAt) ?: 0
        val result = remote()?.playQueue() ?: return null
        if (result !is RemoteResult.Success) return null
        val queue = result.value
        if (queue.songs.isEmpty() || (queue.changedAt ?: 0) <= localChangedAt) return null
        mediaDao.upsertSongs(queue.songs.map { it.toEntity() })
        return RestoredQueue(
            songs = queue.songs,
            currentIndex = queue.songs.indexOfFirst { it.id == queue.currentId }.takeIf { it >= 0 } ?: 0,
            positionMs = queue.positionMs,
        )
    }

    suspend fun persistQueue(state: PlayerUiState) {
        val now = System.currentTimeMillis()
        queueDao.replace(
            state.queue.mapIndexed { index, song ->
                QueueItemEntity(
                    songId = song.id,
                    position = index,
                    isCurrent = index == state.currentIndex,
                    playbackPositionMs = if (index == state.currentIndex) state.positionMs else 0,
                    updatedAt = now,
                )
            },
        )
    }

    suspend fun saveServerQueue(state: PlayerUiState) {
        val remote = remote() ?: return queuePendingMutation(state)
        when (
            remote.saveQueue(
                ids = state.queue.map(Song::id),
                current = state.currentSong?.id,
                positionMs = state.positionMs,
            )
        ) {
            is RemoteResult.Success -> Unit
            is RemoteResult.Failure -> queuePendingMutation(state)
        }
    }

    suspend fun recordStarted(songId: String) {
        val now = System.currentTimeMillis()
        activityDao.addPlayEvent(
            PlayEventEntity(songId = songId, type = PlayEventType.STARTED.name, occurredAt = now),
        )
        remote()?.scrobble(songId, now, submission = false)
    }

    suspend fun submitScrobble(songId: String) {
        val now = System.currentTimeMillis()
        val result = remote()?.scrobble(songId, now, submission = true)
        activityDao.addPlayEvent(
            PlayEventEntity(
                songId = songId,
                type = PlayEventType.COMPLETED.name,
                occurredAt = now,
                synced = result is RemoteResult.Success,
            ),
        )
    }

    private suspend fun queuePendingMutation(state: PlayerUiState) {
        activityDao.upsertMutation(
            PendingMutationEntity(
                id = UUID.randomUUID().toString(),
                type = "SAVE_QUEUE",
                targetId = "current",
                payload = buildString {
                    append(state.currentSong?.id.orEmpty())
                    append('|')
                    append(state.positionMs)
                    append('|')
                    append(state.queue.joinToString(",", transform = Song::id))
                },
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun remote(): LibraryRemoteDataSource? {
        val credentials = credentialsStore.credentials.first() ?: return null
        return runCatching { LibraryRemoteDataSource(apiFactory.authorized(credentials)) }.getOrNull()
    }

    private fun com.cleartune.core.model.ServerProfile?.supportsRawStreaming(): Boolean {
        val parts = this?.apiVersion.orEmpty().split('.')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return false
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return major > 1 || (major == 1 && minor >= 9)
    }
}
