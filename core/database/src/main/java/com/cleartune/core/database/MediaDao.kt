package com.cleartune.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {
    @Query("SELECT * FROM albums ORDER BY COALESCE(createdAt, 0) DESC, name COLLATE NOCASE")
    fun observeAlbums(): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums WHERE id = :id LIMIT 1")
    fun observeAlbum(id: String): Flow<AlbumEntity?>

    @Query("SELECT * FROM artists ORDER BY name COLLATE NOCASE")
    fun observeArtists(): Flow<List<ArtistEntity>>

    @Query("SELECT * FROM artists WHERE id = :id LIMIT 1")
    fun observeArtist(id: String): Flow<ArtistEntity?>

    @Query("SELECT * FROM songs ORDER BY title COLLATE NOCASE")
    fun observeSongs(): Flow<List<SongEntity>>

    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE")
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    fun observePlaylist(id: String): Flow<PlaylistEntity?>

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

    @Transaction
    suspend fun clearStarredFlags(updatedAt: Long) {
        clearSongStars(updatedAt)
        clearAlbumStars(updatedAt)
        clearArtistStars(updatedAt)
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
    @Query("SELECT * FROM downloads ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE songId = :songId LIMIT 1")
    suspend fun forSong(songId: String): DownloadEntity?

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
