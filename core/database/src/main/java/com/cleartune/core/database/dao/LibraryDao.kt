package com.cleartune.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.cleartune.core.database.SourceSnapshotPlanner
import com.cleartune.core.database.missingSourceKeys
import com.cleartune.core.database.entity.AlbumEntity
import com.cleartune.core.database.entity.ArtistEntity
import com.cleartune.core.database.entity.MusicSourceEntity
import com.cleartune.core.database.entity.SyncSessionEntity
import com.cleartune.core.database.entity.TrackArtistCrossRef
import com.cleartune.core.database.entity.TrackEntity
import com.cleartune.core.database.entity.TrackLocationEntity
import com.cleartune.core.database.entity.TrackSearchFtsEntity
import com.cleartune.core.database.model.AlbumRow
import com.cleartune.core.database.model.ArtistRow
import com.cleartune.core.database.model.FolderRow
import com.cleartune.core.database.model.LibraryIngestRecord
import com.cleartune.core.database.model.LibraryTrackRow
import com.cleartune.core.database.model.MediaCatalogRow
import com.cleartune.core.database.model.StableLibraryId
import com.cleartune.core.model.LibraryMutation
import com.cleartune.core.model.LocationType
import com.cleartune.core.model.MutationResult
import com.cleartune.core.model.SourceId
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryReadDao {
    @Query(
        """
        SELECT t.id AS mediaId, t.title, a.title AS albumTitle,
               (SELECT GROUP_CONCAT(ar.name, char(31))
                  FROM track_artists ta JOIN artists ar ON ar.id = ta.artistId
                 WHERE ta.trackId = t.id) AS artistNames,
               t.artworkRef AS artworkUri, chosen.uri AS playbackUri,
               chosen.sourceId AS sourceId, chosen.id AS locationId
          FROM tracks t
          LEFT JOIN albums a ON a.id = t.albumId
          JOIN track_locations chosen ON chosen.id = (
               SELECT candidate.id FROM track_locations candidate
                WHERE candidate.trackId = t.id AND candidate.available = 1
                ORDER BY CASE candidate.type
                    WHEN 'DOWNLOADED_FILE' THEN 0 WHEN 'LOCAL_URI' THEN 1 ELSE 2 END,
                    candidate.id
                LIMIT 1
          )
         WHERE (:category = 'songs')
            OR (:category = 'albums' AND t.albumId IS NOT NULL)
            OR (:category = 'artists' AND EXISTS(
                SELECT 1 FROM track_artists ta WHERE ta.trackId = t.id
            ))
            OR (:category = 'playlists' AND EXISTS(
                SELECT 1 FROM playlist_tracks pt WHERE pt.trackId = t.id
            ))
            OR (:category = 'downloads' AND EXISTS(
                SELECT 1 FROM track_locations dl
                 WHERE dl.trackId = t.id AND dl.available = 1 AND dl.type = 'DOWNLOADED_FILE'
            ))
         ORDER BY CASE :category
                    WHEN 'albums' THEN COALESCE(a.title, '')
                    ELSE t.title
                  END COLLATE NOCASE, t.id
         LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun mediaCatalogPage(category: String, limit: Int, offset: Int): List<MediaCatalogRow>

    @Query(
        """
        SELECT t.id AS mediaId, t.title, a.title AS albumTitle,
               (SELECT GROUP_CONCAT(ar.name, char(31))
                  FROM track_artists ta JOIN artists ar ON ar.id = ta.artistId
                 WHERE ta.trackId = t.id) AS artistNames,
               t.artworkRef AS artworkUri, chosen.uri AS playbackUri,
               chosen.sourceId AS sourceId, chosen.id AS locationId
          FROM tracks t
          LEFT JOIN albums a ON a.id = t.albumId
          JOIN track_locations chosen ON chosen.id = (
               SELECT candidate.id FROM track_locations candidate
                WHERE candidate.trackId = t.id AND candidate.available = 1
                ORDER BY CASE candidate.type
                    WHEN 'DOWNLOADED_FILE' THEN 0 WHEN 'LOCAL_URI' THEN 1 ELSE 2 END,
                    candidate.id
                LIMIT 1
          )
         WHERE t.id = :mediaId
         LIMIT 1
        """,
    )
    suspend fun mediaCatalogItem(mediaId: String): MediaCatalogRow?

    @Query(
        """
        SELECT t.id AS trackId, t.title, t.albumId, a.title AS albumTitle,
               (SELECT GROUP_CONCAT(name, char(31)) FROM (
                    SELECT ar.name AS name FROM artists ar
                    INNER JOIN track_artists ta ON ta.artistId = ar.id
                    WHERE ta.trackId = t.id ORDER BY ar.name COLLATE NOCASE
               )) AS artistNames,
               (SELECT GROUP_CONCAT(artistId, char(31)) FROM (
                    SELECT ta.artistId AS artistId FROM track_artists ta
                    WHERE ta.trackId = t.id ORDER BY ta.artistId
               )) AS artistIds,
               t.artworkRef, t.durationMs,
               (SELECT COUNT(*) FROM track_locations dl
                    WHERE dl.trackId = t.id AND dl.type = 'DOWNLOADED_FILE' AND dl.available = 1) AS downloadedLocations,
               (SELECT GROUP_CONCAT(DISTINCT sl.sourceId) FROM track_locations sl
                    WHERE sl.trackId = t.id AND sl.available = 1) AS sourceIds,
               (SELECT GROUP_CONCAT(relativeFolder, char(31)) FROM (
                    SELECT DISTINCT fl.relativeFolder AS relativeFolder FROM track_locations fl
                    WHERE fl.trackId = t.id AND fl.available = 1 ORDER BY fl.relativeFolder COLLATE NOCASE
               )) AS relativeFolders,
               t.addedAtEpochMs
        FROM tracks t
        LEFT JOIN albums a ON a.id = t.albumId
        WHERE EXISTS (SELECT 1 FROM track_locations l WHERE l.trackId = t.id AND l.available = 1)
        """,
    )
    fun observeTrackRows(): Flow<List<LibraryTrackRow>>

    @Query("SELECT COUNT(*) FROM tracks t WHERE EXISTS (SELECT 1 FROM track_locations l WHERE l.trackId = t.id AND l.available = 1)")
    fun observeSongCount(): Flow<Int>

    @Query("SELECT COUNT(DISTINCT t.albumId) FROM tracks t WHERE t.albumId IS NOT NULL AND EXISTS (SELECT 1 FROM track_locations l WHERE l.trackId = t.id AND l.available = 1)")
    fun observeAlbumCount(): Flow<Int>

    @Query("SELECT COUNT(DISTINCT ta.artistId) FROM track_artists ta INNER JOIN track_locations l ON l.trackId = ta.trackId WHERE l.available = 1")
    fun observeArtistCount(): Flow<Int>

    @Query("SELECT a.id AS albumId, a.title, a.artworkRef FROM albums a WHERE EXISTS (SELECT 1 FROM tracks t INNER JOIN track_locations l ON l.trackId = t.id WHERE t.albumId = a.id AND l.available = 1) ORDER BY a.title COLLATE NOCASE")
    fun observeAlbums(): Flow<List<AlbumRow>>

    @Query("SELECT a.id AS artistId, a.name FROM artists a WHERE EXISTS (SELECT 1 FROM track_artists ta INNER JOIN track_locations l ON l.trackId = ta.trackId WHERE ta.artistId = a.id AND l.available = 1) ORDER BY a.name COLLATE NOCASE")
    fun observeArtists(): Flow<List<ArtistRow>>

    @Query("SELECT trackId FROM track_search_fts WHERE track_search_fts MATCH :matchQuery")
    fun observeSearchTrackIds(matchQuery: String): Flow<List<String>>

    @Query("SELECT relativeFolder, COUNT(DISTINCT trackId) AS trackCount FROM track_locations WHERE available = 1 AND relativeFolder != '' GROUP BY relativeFolder ORDER BY relativeFolder COLLATE NOCASE")
    fun observeFolders(): Flow<List<FolderRow>>

    @Query("SELECT * FROM tracks WHERE id = :trackId")
    suspend fun track(trackId: String): TrackEntity?

    @Query("SELECT * FROM track_locations WHERE trackId = :trackId AND available = 1")
    suspend fun playableLocations(trackId: String): List<TrackLocationEntity>
}

@Dao
abstract class LibraryWriteDao {
    @Upsert
    abstract suspend fun upsertSource(source: MusicSourceEntity)

    @Upsert
    abstract suspend fun upsertTrack(track: TrackEntity)

    @Upsert
    abstract suspend fun upsertLocation(location: TrackLocationEntity)

    @Upsert
    abstract suspend fun upsertAlbum(album: AlbumEntity)

    @Upsert
    abstract suspend fun upsertArtist(artist: ArtistEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertTrackArtist(crossRef: TrackArtistCrossRef)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertSearch(search: TrackSearchFtsEntity)

    @Upsert
    abstract suspend fun upsertSyncSession(session: SyncSessionEntity)

    @Query("SELECT * FROM sync_sessions ORDER BY completedAtEpochMs DESC LIMIT 1")
    abstract suspend fun latestSyncSession(): SyncSessionEntity?

    @Query("DELETE FROM track_artists WHERE trackId = :trackId")
    abstract suspend fun deleteTrackArtists(trackId: String)

    @Query("DELETE FROM track_search_fts WHERE trackId = :trackId")
    abstract suspend fun deleteSearch(trackId: String)

    @Query("SELECT * FROM track_locations WHERE sourceId = :sourceId AND sourceKey = :sourceKey LIMIT 1")
    abstract suspend fun locationIncludingUnavailable(sourceId: String, sourceKey: String): TrackLocationEntity?

    @Query("SELECT * FROM tracks WHERE id = :trackId LIMIT 1")
    abstract suspend fun track(trackId: String): TrackEntity?

    @Query("SELECT title FROM albums WHERE id = :albumId LIMIT 1")
    abstract suspend fun albumTitle(albumId: String): String?

    @Query("SELECT a.name FROM artists a INNER JOIN track_artists ta ON ta.artistId = a.id WHERE ta.trackId = :trackId ORDER BY a.name COLLATE NOCASE")
    abstract suspend fun artistNames(trackId: String): List<String>

    @Query("SELECT sourceKey FROM track_locations WHERE sourceId = :sourceId")
    abstract suspend fun sourceKeys(sourceId: String): List<String>

    @Query("UPDATE track_locations SET available = 0 WHERE sourceId = :sourceId AND sourceKey IN (:sourceKeys) AND available != 0")
    abstract suspend fun markSourceKeysUnavailable(sourceId: String, sourceKeys: List<String>): Int

    @Query("UPDATE track_locations SET available = 0 WHERE id = :locationId AND available != 0")
    abstract suspend fun markLocationUnavailable(locationId: String): Int

    @Query("UPDATE track_locations SET available = 0 WHERE sourceId = :sourceId AND type = 'REMOTE_URL' AND available != 0")
    abstract suspend fun markRemoteLocationsUnavailable(sourceId: String): Int

    @Transaction
    open suspend fun applySourceSnapshot(
        sourceId: SourceId,
        sourceName: String,
        records: List<LibraryIngestRecord>,
        syncedAtEpochMs: Long,
        warningCount: Int = 0,
        retainedSourceKeys: Set<String> = records.mapTo(linkedSetOf(), LibraryIngestRecord::sourceKey),
    ): MutationResult {
        upsertSource(
            MusicSourceEntity(
                id = sourceId.value,
                name = sourceName,
                type = "LOCAL",
                baseUrl = null,
                allowCleartext = false,
                credentialAlias = null,
                enabled = true,
                lastSyncedAtEpochMs = syncedAtEpochMs,
            ),
        )
        var inserted = 0
        var updated = 0
        records.forEach { record ->
            val existing = locationIncludingUnavailable(sourceId.value, record.sourceKey)
            val trackId = existing?.trackId ?: StableLibraryId.track(sourceId, record.sourceKey).value
            val albumId = record.albumTitle?.takeIf(String::isNotBlank)?.let { title ->
                StableLibraryId.album(sourceId, title).value
            }
            val existingTrack = existing?.let { track(it.trackId) }
            val desiredTrack = TrackEntity(
                id = trackId,
                title = record.title,
                durationMs = record.durationMs,
                albumId = albumId,
                artworkRef = record.artworkRef,
                addedAtEpochMs = existingTrack?.addedAtEpochMs ?: record.addedAtEpochMs,
            )
            val desiredLocation = TrackLocationEntity(
                id = existing?.id ?: StableLibraryId.location(sourceId, record.sourceKey).value,
                trackId = trackId,
                sourceId = sourceId.value,
                sourceKey = record.sourceKey,
                type = LocationType.LOCAL_URI.name,
                uri = record.uri,
                available = true,
                sizeBytes = record.sizeBytes,
                etag = null,
                relativeFolder = record.relativeFolder,
                displayName = record.displayName,
                modifiedEpochSeconds = record.modifiedEpochSeconds,
            )
            val plan = if (existing != null && existingTrack != null) {
                SourceSnapshotPlanner.plan(
                    existingTrack = existingTrack,
                    existingLocation = existing,
                    existingArtistNames = artistNames(trackId),
                    desiredAlbumId = albumId,
                    incoming = record,
                    existingAlbumTitle = existingTrack.albumId?.let { albumTitle(it) },
                )
            } else {
                null
            }
            if (plan != null && !plan.requiresWrite) return@forEach
            if (albumId != null && (plan == null || plan.requiresWrite)) {
                upsertAlbum(AlbumEntity(albumId, record.albumTitle.trim(), record.artworkRef))
            }
            upsertTrack(plan?.track ?: desiredTrack)
            upsertLocation(plan?.location ?: desiredLocation)
            deleteTrackArtists(trackId)
            record.artistNames.distinctBy(String::lowercase).forEach { name ->
                val artistId = StableLibraryId.artist(sourceId, name)
                upsertArtist(ArtistEntity(artistId.value, name.trim()))
                insertTrackArtist(TrackArtistCrossRef(trackId, artistId.value))
            }
            deleteSearch(trackId)
            insertSearch(
                TrackSearchFtsEntity(
                    trackId = trackId,
                    title = record.title,
                    albumTitle = record.albumTitle.orEmpty(),
                    artistNames = record.artistNames.joinToString(" "),
                ),
            )
            if (existing == null || existingTrack == null) inserted++ else updated++
        }
        val removed = markMissingSourceKeysUnavailable(
            sourceId = sourceId.value,
            retainedKeys = retainedSourceKeys,
        )
        upsertSyncSession(
            SyncSessionEntity(
                id = "${sourceId.value}:$syncedAtEpochMs",
                sourceId = sourceId.value,
                startedAtEpochMs = syncedAtEpochMs,
                completedAtEpochMs = syncedAtEpochMs,
                phase = "COMPLETED",
                processed = records.size,
                total = records.size,
                warningCount = warningCount,
                errorMessage = null,
            ),
        )
        return MutationResult(inserted = inserted, updated = updated, removed = removed)
    }

    @Transaction
    open suspend fun applyMutation(mutation: LibraryMutation): MutationResult = when (mutation) {
        is LibraryMutation.Upsert -> {
            mutation.tracks.forEach { track ->
                val existing = track(track.id.value)
                val nextAlbumId = track.albumTitle?.takeIf(String::isNotBlank)?.let { title ->
                    StableLibraryId.album(mutation.sourceId, title).value.also { albumId ->
                        upsertAlbum(AlbumEntity(albumId, title.trim(), track.artworkRef))
                    }
                } ?: track.albumId?.value ?: existing?.albumId
                upsertTrack(
                    TrackEntity(
                        id = track.id.value,
                        title = track.title,
                        durationMs = track.durationMs ?: existing?.durationMs,
                        albumId = nextAlbumId,
                        artworkRef = track.artworkRef ?: existing?.artworkRef,
                        addedAtEpochMs = existing?.addedAtEpochMs ?: track.addedAtEpochMs,
                    ),
                )
                if (track.artistNames.isNotEmpty()) {
                    deleteTrackArtists(track.id.value)
                    track.artistNames.distinctBy(String::lowercase).forEach { name ->
                        val artistId = StableLibraryId.artist(mutation.sourceId, name)
                        upsertArtist(ArtistEntity(artistId.value, name.trim()))
                        insertTrackArtist(TrackArtistCrossRef(track.id.value, artistId.value))
                    }
                }
                deleteSearch(track.id.value)
                insertSearch(
                    TrackSearchFtsEntity(
                        trackId = track.id.value,
                        title = track.title,
                        albumTitle = nextAlbumId?.let { albumTitle(it) }.orEmpty(),
                        artistNames = artistNames(track.id.value).joinToString(" "),
                    ),
                )
            }
            mutation.locations.forEach { location ->
                upsertLocation(
                    TrackLocationEntity(
                        id = location.id.value,
                        trackId = location.trackId.value,
                        sourceId = location.sourceId.value,
                        sourceKey = location.sourceKey,
                        type = location.type.name,
                        uri = location.uri,
                        available = location.available,
                        sizeBytes = location.sizeBytes,
                        etag = location.etag,
                        relativeFolder = "",
                        displayName = location.uri.substringAfterLast('/'),
                        modifiedEpochSeconds = 0,
                    ),
                )
            }
            MutationResult(inserted = mutation.tracks.size)
        }
        is LibraryMutation.RetainSourceKeys -> {
            val removed = markMissingSourceKeysUnavailable(mutation.sourceId.value, mutation.retainedSourceKeys)
            MutationResult(removed = removed)
        }
    }

    private suspend fun markMissingSourceKeysUnavailable(sourceId: String, retainedKeys: Collection<String>): Int =
        missingSourceKeys(sourceKeys(sourceId), retainedKeys)
            .chunked(SQLITE_SAFE_BATCH_SIZE)
            .sumOf { keys -> markSourceKeysUnavailable(sourceId, keys) }

    private companion object {
        const val SQLITE_SAFE_BATCH_SIZE = 400
    }
}
