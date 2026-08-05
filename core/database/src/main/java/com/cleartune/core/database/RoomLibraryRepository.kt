package com.cleartune.core.database

import com.cleartune.core.contracts.LibraryRepository
import com.cleartune.core.contracts.LibraryWriteGateway
import com.cleartune.core.contracts.PlaybackLibraryRepository
import com.cleartune.core.contracts.PlaybackHistoryRecord
import com.cleartune.core.contracts.PlaybackHistoryRecorder
import com.cleartune.core.contracts.SourceRepository
import com.cleartune.core.contracts.SourceWriteGateway
import com.cleartune.core.database.dao.LibraryReadDao
import com.cleartune.core.database.dao.LibraryWriteDao
import com.cleartune.core.database.dao.SourceDao
import com.cleartune.core.database.dao.PlaybackDao
import com.cleartune.core.database.entity.PlaybackHistoryEntity
import com.cleartune.core.database.entity.MusicSourceEntity
import com.cleartune.core.database.entity.SyncSessionEntity
import com.cleartune.core.database.model.AlbumRow
import com.cleartune.core.database.model.ArtistRow
import com.cleartune.core.database.model.FolderRow
import com.cleartune.core.database.model.LibraryIngestRecord
import com.cleartune.core.database.model.LibraryTrackRow
import com.cleartune.core.database.model.belongsToFolder
import com.cleartune.core.database.model.hasArtist
import com.cleartune.core.database.model.toDomain
import com.cleartune.core.database.model.toTrackSummary
import com.cleartune.core.model.AlbumId
import com.cleartune.core.model.ArtistId
import com.cleartune.core.model.CredentialAlias
import com.cleartune.core.model.LibraryHome
import com.cleartune.core.model.LibraryMutation
import com.cleartune.core.model.LocationId
import com.cleartune.core.model.MusicSource
import com.cleartune.core.model.MutationResult
import com.cleartune.core.model.PlayableTrack
import com.cleartune.core.model.SearchResults
import com.cleartune.core.model.SongQuery
import com.cleartune.core.model.SongSort
import com.cleartune.core.model.SourceId
import com.cleartune.core.model.SourceMutation
import com.cleartune.core.model.SourceType
import com.cleartune.core.model.Track
import com.cleartune.core.model.TrackId
import com.cleartune.core.model.TrackLocation
import com.cleartune.core.model.TrackSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf

interface LibrarySnapshotStore {
    suspend fun applyLocalSnapshot(
        sourceId: SourceId,
        sourceName: String,
        records: List<LibraryIngestRecord>,
        syncedAtEpochMs: Long,
        warningCount: Int = 0,
        retainedSourceKeys: Set<String> = records.mapTo(linkedSetOf(), LibraryIngestRecord::sourceKey),
    ): MutationResult
}

interface LibraryBrowseStore {
    fun observeAlbums(): Flow<List<AlbumRow>>
    fun observeArtists(): Flow<List<ArtistRow>>
    fun observeFolders(): Flow<List<FolderRow>>
    fun observeFolderTracks(relativeFolder: String): Flow<List<TrackSummary>>
    fun observeArtistTracks(artistId: ArtistId): Flow<List<TrackSummary>>
    fun observeArtistAlbums(artistId: ArtistId): Flow<List<AlbumRow>>
}

interface SyncSessionStore {
    suspend fun recordSyncSession(
        sessionId: String,
        sourceId: SourceId,
        startedAtEpochMs: Long,
        completedAtEpochMs: Long?,
        phase: String,
        processed: Int = 0,
        total: Int = 0,
        warningCount: Int = 0,
        errorMessage: String? = null,
    )
}

class RoomLibraryRepository(
    private val readDao: LibraryReadDao,
    private val writeDao: LibraryWriteDao,
    private val sourceDao: SourceDao,
    private val playbackDao: PlaybackDao? = null,
) : LibraryRepository,
    LibraryWriteGateway,
    PlaybackLibraryRepository,
    SourceRepository,
    SourceWriteGateway,
    LibrarySnapshotStore,
    LibraryBrowseStore,
    SyncSessionStore,
    PlaybackHistoryRecorder {

    constructor(database: ClearTuneDatabase) : this(
        database.libraryReadDao(),
        database.libraryWriteDao(),
        database.sourceDao(),
        database.playbackDao(),
    )

    override fun observeLibraryHome(): Flow<LibraryHome> = combine(
        readDao.observeSongCount(),
        readDao.observeAlbumCount(),
        readDao.observeArtistCount(),
        readDao.observeTrackRows(),
        playbackDao?.observeRecentHistory(HISTORY_LIMIT) ?: flowOf(emptyList()),
    ) { songCount, albumCount, artistCount, rows, history ->
        LibraryHome(
            songCount = songCount,
            albumCount = albumCount,
            artistCount = artistCount,
            recentAdded = rows.sortedByDescending(LibraryTrackRow::addedAtEpochMs).take(4).map(LibraryTrackRow::toTrackSummary),
            recentPlayed = history.asSequence()
                .map(PlaybackHistoryEntity::trackId)
                .distinct()
                .mapNotNull { id -> rows.firstOrNull { it.trackId == id } }
                .take(HOME_RECENT_LIMIT)
                .map(LibraryTrackRow::toTrackSummary)
                .toList(),
        )
    }

    override suspend fun record(record: PlaybackHistoryRecord) {
        val dao = requireNotNull(playbackDao) { "Playback history persistence is unavailable" }
        dao.recordHistorySession(
            PlaybackHistoryEntity(
                id = stableHistoryId(record.sessionKey),
                trackId = record.trackId.value,
                playedAtEpochMs = record.playedAtEpochMs,
                completed = record.completed,
            ),
        )
    }

    override fun observeSongs(query: SongQuery): Flow<List<TrackSummary>> = readDao.observeTrackRows().map { rows ->
        val sourceFilter = query.sourceId
        rows.asSequence()
            .filter { row -> sourceFilter == null || row.sourceIds.split(',').contains(sourceFilter.value) }
            .filter { row -> !query.downloadedOnly || row.downloadedLocations > 0 }
            .filter { row ->
                query.text.isBlank() || listOf(row.title, row.albumTitle, row.artistNames)
                    .filterNotNull()
                    .any { it.contains(query.text, ignoreCase = true) }
            }
            .let { sequence ->
                val comparator = when (query.sort) {
                    SongSort.TITLE -> compareBy<LibraryTrackRow> { it.title.lowercase() }
                    SongSort.ARTIST -> compareBy { it.artistNames.orEmpty().lowercase() }
                    SongSort.ALBUM -> compareBy { it.albumTitle.orEmpty().lowercase() }
                    SongSort.DATE_ADDED -> compareBy { it.addedAtEpochMs }
                    SongSort.DURATION -> compareBy { it.durationMs ?: Long.MAX_VALUE }
                }
                sequence.sortedWith(if (query.ascending) comparator else comparator.reversed())
            }
            .map(LibraryTrackRow::toTrackSummary)
            .toList()
    }

    override fun observeAlbumTracks(albumId: AlbumId): Flow<List<TrackSummary>> = readDao.observeTrackRows().map { rows ->
        rows.filter { it.albumId == albumId.value }.map(LibraryTrackRow::toTrackSummary)
    }

    override fun search(query: String): Flow<SearchResults> {
        val needle = query.trim()
        val matchQuery = ftsMatchQuery(needle) ?: return flowOf(SearchResults())
        return combine(
            readDao.observeTrackRows(),
            readDao.observeAlbums(),
            readDao.observeArtists(),
            readDao.observeSearchTrackIds(matchQuery),
        ) { tracks, albums, artists, matchingTrackIds ->
            val matchingIds = matchingTrackIds.toHashSet()
            SearchResults(
                songs = tracks.filter { it.trackId in matchingIds }.map(LibraryTrackRow::toTrackSummary),
                albums = albums.filter { it.title.contains(needle, true) }.map(AlbumRow::toDomain),
                artists = artists.filter { it.name.contains(needle, true) }.map(ArtistRow::toDomain),
            )
        }
    }

    override suspend fun applyLibraryMutation(mutation: LibraryMutation): MutationResult = writeDao.applyMutation(mutation)

    override suspend fun getPlayableTrack(trackId: TrackId): PlayableTrack? {
        val track = readDao.track(trackId.value) ?: return null
        return PlayableTrack(
            track = Track(
                id = TrackId(track.id),
                title = track.title,
                durationMs = track.durationMs,
                albumId = track.albumId?.let(::AlbumId),
                artworkRef = track.artworkRef,
                addedAtEpochMs = track.addedAtEpochMs,
                albumTitle = track.albumId?.let { writeDao.albumTitle(it) },
                artistNames = writeDao.artistNames(track.id),
            ),
            locations = readDao.playableLocations(track.id).map { location ->
                TrackLocation(
                    id = LocationId(location.id),
                    trackId = TrackId(location.trackId),
                    sourceId = SourceId(location.sourceId),
                    sourceKey = location.sourceKey,
                    type = com.cleartune.core.model.LocationType.valueOf(location.type),
                    uri = location.uri,
                    available = location.available,
                    sizeBytes = location.sizeBytes,
                    etag = location.etag,
                )
            },
        )
    }

    override fun observeSources(): Flow<List<MusicSource>> = sourceDao.observeSources().map { entities -> entities.map(MusicSourceEntity::toDomain) }

    override suspend fun getSource(sourceId: SourceId): MusicSource? = sourceDao.source(sourceId.value)?.toDomain()

    override suspend fun applySourceMutation(mutation: SourceMutation): MutationResult = when (mutation) {
        is SourceMutation.Upsert -> {
            sourceDao.upsert(mutation.source.toEntity())
            MutationResult(updated = 1)
        }
        is SourceMutation.Remove -> MutationResult(removed = sourceDao.softDelete(mutation.sourceId.value))
    }

    override suspend fun applyLocalSnapshot(
        sourceId: SourceId,
        sourceName: String,
        records: List<LibraryIngestRecord>,
        syncedAtEpochMs: Long,
        warningCount: Int,
        retainedSourceKeys: Set<String>,
    ): MutationResult = writeDao.applySourceSnapshot(
        sourceId,
        sourceName,
        records,
        syncedAtEpochMs,
        warningCount,
        retainedSourceKeys,
    )

    override fun observeAlbums(): Flow<List<AlbumRow>> = readDao.observeAlbums()
    override fun observeArtists(): Flow<List<ArtistRow>> = readDao.observeArtists()
    override fun observeFolders(): Flow<List<FolderRow>> = readDao.observeFolders()
    override fun observeFolderTracks(relativeFolder: String): Flow<List<TrackSummary>> = readDao.observeTrackRows().map { rows ->
        rows.filter { row -> row.belongsToFolder(relativeFolder) }
            .map(LibraryTrackRow::toTrackSummary)
    }
    override fun observeArtistTracks(artistId: ArtistId): Flow<List<TrackSummary>> = readDao.observeTrackRows().map { rows ->
        rows.filter { it.hasArtist(artistId) }.map(LibraryTrackRow::toTrackSummary)
    }
    override fun observeArtistAlbums(artistId: ArtistId): Flow<List<AlbumRow>> = combine(
        readDao.observeTrackRows(),
        readDao.observeAlbums(),
    ) { tracks, albums ->
        val albumIds = tracks.filter { it.hasArtist(artistId) }.mapNotNullTo(hashSetOf(), LibraryTrackRow::albumId)
        albums.filter { it.albumId in albumIds }
    }

    override suspend fun recordSyncSession(
        sessionId: String,
        sourceId: SourceId,
        startedAtEpochMs: Long,
        completedAtEpochMs: Long?,
        phase: String,
        processed: Int,
        total: Int,
        warningCount: Int,
        errorMessage: String?,
    ) = writeDao.upsertSyncSession(
        SyncSessionEntity(
            id = sessionId,
            sourceId = sourceId.value,
            startedAtEpochMs = startedAtEpochMs,
            completedAtEpochMs = completedAtEpochMs,
            phase = phase,
            processed = processed,
            total = total,
            warningCount = warningCount,
            errorMessage = errorMessage,
        ),
    )
}

private const val HISTORY_LIMIT = 20
private const val HOME_RECENT_LIMIT = 4

private fun stableHistoryId(sessionKey: String): Long =
    (java.util.UUID.nameUUIDFromBytes("cleartune-history:$sessionKey".toByteArray(Charsets.UTF_8))
        .mostSignificantBits and Long.MAX_VALUE).takeIf { it != 0L } ?: 1L

private fun MusicSourceEntity.toDomain(): MusicSource = MusicSource(
    id = SourceId(id),
    name = name,
    type = SourceType.valueOf(type),
    baseUrl = baseUrl,
    allowCleartext = allowCleartext,
    credentialAlias = credentialAlias?.let(::CredentialAlias),
    enabled = enabled,
    lastSyncedAtEpochMs = lastSyncedAtEpochMs,
)

private fun MusicSource.toEntity(): MusicSourceEntity = MusicSourceEntity(
    id = id.value,
    name = name,
    type = type.name,
    baseUrl = baseUrl,
    allowCleartext = allowCleartext,
    credentialAlias = credentialAlias?.value,
    enabled = enabled,
    lastSyncedAtEpochMs = lastSyncedAtEpochMs,
    removed = false,
)
