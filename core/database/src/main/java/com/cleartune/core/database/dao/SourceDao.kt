package com.cleartune.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.cleartune.core.database.entity.MusicSourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {
    @Query("SELECT * FROM music_sources WHERE removed = 0 AND id != 'offline-downloads' ORDER BY type, name COLLATE NOCASE")
    fun observeSources(): Flow<List<MusicSourceEntity>>

    @Query("SELECT * FROM music_sources WHERE id = :sourceId AND removed = 0 LIMIT 1")
    suspend fun source(sourceId: String): MusicSourceEntity?

    @Query("SELECT * FROM music_sources WHERE id = :sourceId AND removed = 1 LIMIT 1")
    suspend fun tombstone(sourceId: String): MusicSourceEntity?

    @Query("SELECT * FROM music_sources WHERE removed = 1")
    suspend fun tombstones(): List<MusicSourceEntity>

    @Upsert
    suspend fun upsert(source: MusicSourceEntity)

    @Query("UPDATE music_sources SET removed = 1, enabled = 0 WHERE id = :sourceId AND removed = 0")
    suspend fun softDelete(sourceId: String): Int

    @Query("UPDATE music_sources SET credentialAlias = NULL WHERE id = :sourceId AND removed = 1")
    suspend fun clearTombstoneCredential(sourceId: String): Int
}
