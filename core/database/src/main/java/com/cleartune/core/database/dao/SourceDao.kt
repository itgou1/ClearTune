package com.cleartune.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.cleartune.core.database.entity.MusicSourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {
    @Query("SELECT * FROM music_sources ORDER BY type, name COLLATE NOCASE")
    fun observeSources(): Flow<List<MusicSourceEntity>>

    @Query("SELECT * FROM music_sources WHERE id = :sourceId LIMIT 1")
    suspend fun source(sourceId: String): MusicSourceEntity?

    @Upsert
    suspend fun upsert(source: MusicSourceEntity)

    @Query("DELETE FROM music_sources WHERE id = :sourceId")
    suspend fun delete(sourceId: String): Int
}
