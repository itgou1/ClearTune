package com.cleartune.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {
    @Query("SELECT (SELECT COUNT(*) FROM songs) + (SELECT COUNT(*) FROM albums) + (SELECT COUNT(*) FROM artists)")
    suspend fun libraryItemCount(): Int

    @Query("SELECT COUNT(*) FROM search_documents")
    suspend fun searchDocumentCount(): Int

    @Query("SELECT COUNT(*) FROM search_documents WHERE pinyin LIKE '%' || :marker || '%'")
    suspend fun searchDocumentFormatCount(marker: String): Int

    @Query(
        """
        SELECT * FROM search_documents
        WHERE search_documents MATCH :matchQuery
          AND entityType = :entityType
        LIMIT :limit
        """,
    )
    suspend fun searchDocuments(
        entityType: String,
        matchQuery: String,
        limit: Int,
    ): List<SearchDocumentEntity>

    @Query("SELECT * FROM search_documents LIMIT :limit")
    suspend fun searchSuggestionDocuments(limit: Int): List<SearchDocumentEntity>

    @Insert
    suspend fun insertSearchDocuments(items: List<SearchDocumentEntity>)

    @Query("DELETE FROM search_documents")
    suspend fun clearSearchDocuments()

    @Query("DELETE FROM search_documents WHERE entityType = :entityType AND entityId IN (:entityIds)")
    suspend fun deleteSearchDocuments(entityType: String, entityIds: List<String>)

    @Transaction
    suspend fun replaceSearchDocuments(items: List<SearchDocumentEntity>) {
        clearSearchDocuments()
        if (items.isNotEmpty()) insertSearchDocuments(items)
    }

    @Transaction
    suspend fun upsertSearchDocuments(items: List<SearchDocumentEntity>) {
        items.groupBy(SearchDocumentEntity::entityType).forEach { (entityType, documents) ->
            deleteSearchDocuments(entityType, documents.map(SearchDocumentEntity::entityId))
        }
        if (items.isNotEmpty()) insertSearchDocuments(items)
    }

    @Query("SELECT * FROM albums ORDER BY COALESCE(createdAt, 0) DESC, name COLLATE NOCASE")
    fun observeAlbums(): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums WHERE id = :id LIMIT 1")
    fun observeAlbum(id: String): Flow<AlbumEntity?>

    @Query("SELECT * FROM artists ORDER BY name COLLATE NOCASE")
    fun observeArtists(): Flow<List<ArtistEntity>>

    @Query("SELECT * FROM artists WHERE id = :id LIMIT 1")
    fun observeArtist(id: String): Flow<ArtistEntity?>

    @Query("SELECT * FROM artists WHERE id IN (:ids)")
    suspend fun artistsByIds(ids: List<String>): List<ArtistEntity>

    @Query("SELECT * FROM songs ORDER BY title COLLATE NOCASE")
    fun observeSongs(): Flow<List<SongEntity>>

    @Query("SELECT * FROM albums WHERE id IN (:ids)")
    suspend fun albumsByIds(ids: List<String>): List<AlbumEntity>

    @Query("SELECT * FROM songs WHERE id IN (:ids)")
    suspend fun songsByIds(ids: List<String>): List<SongEntity>

    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE")
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists")
    suspend fun allPlaylists(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    fun observePlaylist(id: String): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlists WHERE id IN (:ids)")
    suspend fun playlistsByIds(ids: List<String>): List<PlaylistEntity>

    @Query("SELECT songs.* FROM songs INNER JOIN playlist_songs ON songs.id = playlist_songs.songId WHERE playlist_songs.playlistId = :playlistId ORDER BY playlist_songs.position")
    fun observePlaylistSongs(playlistId: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE albumId = :albumId ORDER BY discNumber, trackNumber, title")
    fun observeSongsForAlbum(albumId: String): Flow<List<SongEntity>>

    @Query(
        """
        SELECT * FROM songs
        WHERE artistId = :artistId
           OR (
               artistId IS NULL
               AND artistName COLLATE NOCASE = (
                   SELECT name FROM artists WHERE id = :artistId LIMIT 1
               )
           )
        ORDER BY playCount DESC, discNumber, trackNumber, title
        """,
    )
    fun observeSongsForArtist(artistId: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE id = :id LIMIT 1")
    suspend fun song(id: String): SongEntity?

    @Query("UPDATE songs SET starredAt = :starredAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateSongStarred(id: String, starredAt: Long?, updatedAt: Long)

    @Query("UPDATE albums SET starredAt = :starredAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateAlbumStarred(id: String, starredAt: Long?, updatedAt: Long)

    @Query("UPDATE artists SET starredAt = :starredAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateArtistStarred(id: String, starredAt: Long?, updatedAt: Long)

    @Query("UPDATE songs SET starredAt = NULL, updatedAt = :updatedAt WHERE starredAt IS NOT NULL")
    suspend fun clearSongStars(updatedAt: Long)

    @Query("UPDATE albums SET starredAt = NULL, updatedAt = :updatedAt WHERE starredAt IS NOT NULL")
    suspend fun clearAlbumStars(updatedAt: Long)

    @Query("UPDATE artists SET starredAt = NULL, updatedAt = :updatedAt WHERE starredAt IS NOT NULL")
    suspend fun clearArtistStars(updatedAt: Long)

    @Query("SELECT * FROM songs WHERE starredAt IS NOT NULL")
    suspend fun starredSongs(): List<SongEntity>

    @Query("SELECT * FROM albums WHERE starredAt IS NOT NULL")
    suspend fun starredAlbums(): List<AlbumEntity>

    @Query("SELECT * FROM artists WHERE starredAt IS NOT NULL")
    suspend fun starredArtists(): List<ArtistEntity>

    @Transaction
    suspend fun clearStarredFlags(updatedAt: Long) {
        clearSongStars(updatedAt)
        clearAlbumStars(updatedAt)
        clearArtistStars(updatedAt)
    }

    /** One observable update for the whole favorite snapshot; unchanged snapshots do no writes. */
    @Transaction
    suspend fun replaceFavoritesIfChanged(
        songs: List<SongEntity>,
        albums: List<AlbumEntity>,
        artists: List<ArtistEntity>,
        now: Long,
    ): Boolean {
        val currentSongs = starredSongs().associateBy(SongEntity::id)
        val currentAlbums = starredAlbums().associateBy(AlbumEntity::id)
        val currentArtists = starredArtists().associateBy(ArtistEntity::id)
        val previousSongs = songsByIds(songs.map(SongEntity::id)).associateBy(SongEntity::id)
        val preservedSongs = songs.map { incoming ->
            val previous = previousSongs[incoming.id]
            incoming.copy(
                playCount = maxOf(incoming.playCount, previous?.playCount ?: 0),
                lastPlayedAt = listOfNotNull(incoming.lastPlayedAt, previous?.lastPlayedAt).maxOrNull(),
                starredAt = incoming.starredAt ?: previous?.starredAt ?: now,
            )
        }
        val normalizedAlbums = albums.map { incoming ->
            incoming.copy(starredAt = incoming.starredAt ?: currentAlbums[incoming.id]?.starredAt ?: now)
        }
        val normalizedArtists = artists.map { incoming ->
            incoming.copy(starredAt = incoming.starredAt ?: currentArtists[incoming.id]?.starredAt ?: now)
        }
        val songsMatch = currentSongs.size == preservedSongs.size && preservedSongs.all { incoming ->
            currentSongs[incoming.id]?.copy(updatedAt = incoming.updatedAt) == incoming
        }
        val albumsMatch = currentAlbums.size == normalizedAlbums.size && normalizedAlbums.all { incoming ->
            currentAlbums[incoming.id]?.copy(updatedAt = incoming.updatedAt) == incoming
        }
        val artistsMatch = currentArtists.size == normalizedArtists.size && normalizedArtists.all { incoming ->
            currentArtists[incoming.id]?.copy(updatedAt = incoming.updatedAt) == incoming
        }
        if (songsMatch && albumsMatch && artistsMatch) return false

        clearStarredFlags(now)
        upsertSongs(preservedSongs)
        upsertAlbums(normalizedAlbums)
        upsertArtists(normalizedArtists)
        return true
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAlbums(items: List<AlbumEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertArtists(items: List<ArtistEntity>)

    @Query("DELETE FROM artists WHERE id = :id")
    suspend fun deleteArtist(id: String)

    @Query("DELETE FROM artists")
    suspend fun clearArtists()

    @Transaction
    suspend fun replaceArtists(items: List<ArtistEntity>) {
        clearArtists()
        upsertArtists(items)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSongs(items: List<SongEntity>)

    @Query("DELETE FROM songs")
    suspend fun clearSongs()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylists(items: List<PlaylistEntity>)

    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    suspend fun playlist(id: String): PlaylistEntity?

    /** A partial detail response must not erase artwork supplied by the playlist listing. */
    @Transaction
    suspend fun upsertPlaylistDetail(item: PlaylistEntity): PlaylistEntity {
        val merged = item.copy(coverArtId = item.coverArtId?.takeIf { it.isNotBlank() }
            ?: playlist(item.id)?.coverArtId)
        upsertPlaylists(listOf(merged))
        return merged
    }

    @Query("DELETE FROM playlists")
    suspend fun clearPlaylists()

    @Query(
        """
        DELETE FROM playlist_songs
        WHERE playlistId NOT IN (SELECT id FROM playlists)
           OR songId NOT IN (SELECT id FROM songs)
        """,
    )
    suspend fun clearOrphanedPlaylistSongs()

    @Transaction
    suspend fun replacePlaylists(items: List<PlaylistEntity>) {
        clearPlaylists()
        upsertPlaylists(items)
        clearOrphanedPlaylistSongs()
    }

    /** The list endpoint may omit artwork already supplied by a detail response. */
    @Transaction
    suspend fun replacePlaylistsIfChanged(items: List<PlaylistEntity>): Boolean {
        val current = allPlaylists().associateBy(PlaylistEntity::id)
        val merged = items.map { incoming ->
            incoming.copy(coverArtId = incoming.coverArtId?.takeIf(String::isNotBlank)
                ?: current[incoming.id]?.coverArtId)
        }
        val unchanged = current.size == merged.size && merged.all { incoming ->
            current[incoming.id]?.copy(updatedAt = incoming.updatedAt) == incoming
        }
        if (unchanged) return false
        replacePlaylists(merged)
        return true
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylistSongs(items: List<PlaylistSongEntity>)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun clearPlaylistSongs(playlistId: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: String)

    @Query("DELETE FROM albums")
    suspend fun clearAlbums()

    @Query("DELETE FROM albums WHERE id = :id")
    suspend fun deleteAlbum(id: String)

    @Transaction
    suspend fun replaceAlbums(items: List<AlbumEntity>) {
        clearAlbums()
        upsertAlbums(items)
    }

    @Transaction
    suspend fun replaceLibrary(
        albums: List<AlbumEntity>,
        artists: List<ArtistEntity>,
        songs: List<SongEntity>,
    ) {
        clearAlbums()
        clearArtists()
        clearSongs()
        upsertAlbums(albums)
        upsertArtists(artists)
        upsertSongs(songs)
        clearOrphanedPlaylistSongs()
    }

    @Transaction
    suspend fun replacePlaylistSongs(playlistId: String, items: List<PlaylistSongEntity>) {
        clearPlaylistSongs(playlistId)
        upsertPlaylistSongs(items)
    }
}

@Dao
interface QueueDao {
    @Query("SELECT * FROM queue_items WHERE queueId = :queueId ORDER BY position")
    fun observeQueue(queueId: String = "current"): Flow<List<QueueItemEntity>>

    @Query("SELECT * FROM queue_items WHERE queueId = :queueId ORDER BY position")
    suspend fun queue(queueId: String = "current"): List<QueueItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(items: List<QueueItemEntity>)

    @Query("DELETE FROM queue_items WHERE queueId = :queueId")
    suspend fun clear(queueId: String = "current")

    @Transaction
    suspend fun replace(items: List<QueueItemEntity>) {
        clear()
        upsert(items)
    }
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads WHERE requestId = :requestId LIMIT 1")
    suspend fun forRequest(requestId: String): DownloadEntity?

    @Query("SELECT * FROM downloads")
    suspend fun all(): List<DownloadEntity>

    // Workers may update their own live request, never recreate a deleted/replaced task.
    @Query("""UPDATE downloads SET state = :state, bytesDownloaded = :bytes,
        totalBytes = :total, localUri = :uri, failureReason = :reason, updatedAt = :now
        WHERE requestId = :requestId AND state IN ('QUEUED', 'DOWNLOADING')""")
    suspend fun updateProgress(requestId: String, state: String, bytes: Long, total: Long?,
        uri: String?, reason: String?, now: Long): Int

    @Query("""UPDATE downloads SET state = :state, failureReason = :reason, updatedAt = :now
        WHERE requestId = :requestId AND state = :previousState AND updatedAt = :previousTime""")
    suspend fun reconcile(requestId: String, previousState: String, previousTime: Long,
        state: String, reason: String?, now: Long): Int

    @Query("UPDATE downloads SET state = 'PAUSED', failureReason = '已退出账号，重新登录后可继续下载' WHERE state IN ('QUEUED', 'DOWNLOADING')")
    suspend fun pauseActiveDownloads()

    @Query("SELECT * FROM downloads ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE songId = :songId LIMIT 1")
    suspend fun forSong(songId: String): DownloadEntity?

    @Query("UPDATE downloads SET failureReason = NULL WHERE songId = :songId AND state = 'COMPLETED' AND failureReason = :warning")
    suspend fun clearArtworkWarning(songId: String, warning: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: DownloadEntity)

    @Query("DELETE FROM downloads WHERE requestId = :requestId")
    suspend fun delete(requestId: String)
}

@Dao
interface ActivityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addPlayEvent(event: PlayEventEntity): Long

    @Query("SELECT * FROM play_events WHERE synced = 0 ORDER BY occurredAt LIMIT :limit")
    suspend fun pendingPlayEvents(limit: Int = 100): List<PlayEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMutation(mutation: PendingMutationEntity)

    @Query("SELECT * FROM pending_mutations ORDER BY createdAt LIMIT :limit")
    suspend fun pendingMutations(limit: Int = 100): List<PendingMutationEntity>

    @Query("DELETE FROM pending_mutations WHERE id = :id")
    suspend fun deleteMutation(id: String)
}

data class CachedLyrics(
    val cache: LyricsCacheEntity,
    val lines: List<LyricLineEntity>,
)

@Dao
interface LyricsDao {
    @Query("DELETE FROM lyrics_cache WHERE serverUrl = :serverUrl AND username = :username AND songId = :songId")
    suspend fun invalidate(serverUrl: String, username: String, songId: String)

    @Query(
        """
        SELECT * FROM lyrics_cache
        WHERE serverUrl = :serverUrl AND username = :username AND songId = :songId
        LIMIT 1
        """,
    )
    suspend fun cache(serverUrl: String, username: String, songId: String): LyricsCacheEntity?

    @Query(
        """
        SELECT * FROM lyric_lines
        WHERE serverUrl = :serverUrl AND username = :username AND songId = :songId
        ORDER BY position
        """,
    )
    suspend fun lines(serverUrl: String, username: String, songId: String): List<LyricLineEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCache(item: LyricsCacheEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLines(items: List<LyricLineEntity>)

    @Query(
        """
        DELETE FROM lyric_lines
        WHERE serverUrl = :serverUrl AND username = :username AND songId = :songId
        """,
    )
    suspend fun deleteLines(serverUrl: String, username: String, songId: String)

    @Transaction
    suspend fun lyrics(serverUrl: String, username: String, songId: String): CachedLyrics? {
        val cache = cache(serverUrl, username, songId) ?: return null
        return CachedLyrics(cache, lines(serverUrl, username, songId))
    }

    @Transaction
    suspend fun replace(item: LyricsCacheEntity, lines: List<LyricLineEntity>) {
        upsertCache(item)
        deleteLines(item.serverUrl, item.username, item.songId)
        if (lines.isNotEmpty()) insertLines(lines)
    }
}
