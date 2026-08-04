package com.cleartune.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.cleartune.core.database.entity.AppSettingsEntity
import com.cleartune.core.database.entity.DownloadEntity
import com.cleartune.core.database.entity.PlaybackHistoryEntity
import com.cleartune.core.database.entity.PlaybackQueueEntity
import com.cleartune.core.database.entity.PlaybackQueueItemEntity
import com.cleartune.core.database.entity.PlaybackStateEntity
import com.cleartune.core.database.entity.PlaylistEntity
import com.cleartune.core.database.entity.PlaylistTrackCrossRef
import kotlinx.coroutines.flow.Flow

data class PlaylistSummaryRow(val id: String, val name: String, val trackCount: Int)

@Dao
interface PlaylistDao {
    @Query("SELECT p.id, p.name, COUNT(pt.id) AS trackCount FROM playlists p LEFT JOIN playlist_tracks pt ON pt.playlistId = p.id GROUP BY p.id ORDER BY p.createdAtEpochMs DESC")
    fun observePlaylists(): Flow<List<PlaylistSummaryRow>>

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position")
    fun observeItems(playlistId: String): Flow<List<PlaylistTrackCrossRef>>

    @Upsert suspend fun upsertPlaylist(playlist: PlaylistEntity)
    @Upsert suspend fun upsertItems(items: List<PlaylistTrackCrossRef>)
    @Query("DELETE FROM playlist_tracks WHERE id = :itemId") suspend fun deleteItem(itemId: String): Int
    @Query("DELETE FROM playlists WHERE id = :playlistId") suspend fun deletePlaylist(playlistId: String): Int
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY updatedAtEpochMs DESC")
    fun observeDownloads(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :downloadId LIMIT 1")
    suspend fun download(downloadId: String): DownloadEntity?

    @Upsert suspend fun upsert(download: DownloadEntity)
    @Query("DELETE FROM downloads WHERE id = :downloadId") suspend fun delete(downloadId: String): Int
}

@Dao
interface PlaybackDao {
    @Query("SELECT * FROM playback_queues WHERE id = :queueId LIMIT 1")
    fun observeQueue(queueId: String = DEFAULT_QUEUE_ID): Flow<PlaybackQueueEntity?>

    @Query("SELECT * FROM playback_queue_items WHERE queueId = :queueId ORDER BY position")
    fun observeQueueItems(queueId: String = DEFAULT_QUEUE_ID): Flow<List<PlaybackQueueItemEntity>>

    @Query("SELECT * FROM playback_state WHERE id = 0 LIMIT 1")
    fun observePlaybackState(): Flow<PlaybackStateEntity?>

    @Query("SELECT * FROM playback_history ORDER BY playedAtEpochMs DESC LIMIT :limit")
    fun observeRecentHistory(limit: Int): Flow<List<PlaybackHistoryEntity>>

    @Upsert suspend fun upsertQueue(queue: PlaybackQueueEntity)
    @Upsert suspend fun upsertQueueItems(items: List<PlaybackQueueItemEntity>)
    @Upsert suspend fun upsertPlaybackState(state: PlaybackStateEntity)
    @Upsert suspend fun upsertHistory(history: PlaybackHistoryEntity)
    @Query("DELETE FROM playback_queue_items WHERE queueId = :queueId") suspend fun clearQueueItems(queueId: String = DEFAULT_QUEUE_ID): Int

    companion object { const val DEFAULT_QUEUE_ID = "default" }
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM app_settings WHERE id = 0 LIMIT 1")
    fun observeSettings(): Flow<AppSettingsEntity?>

    @Upsert suspend fun upsert(settings: AppSettingsEntity)
}
