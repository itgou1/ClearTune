package com.cleartune.app

import androidx.room.withTransaction
import com.cleartune.core.contracts.CredentialStore
import com.cleartune.core.database.ClearTuneDatabase
import com.cleartune.core.database.entity.DownloadEntity
import com.cleartune.core.database.entity.MusicSourceEntity
import com.cleartune.core.database.entity.TrackLocationEntity
import com.cleartune.core.model.DownloadId
import com.cleartune.core.model.CredentialAlias
import com.cleartune.core.model.DownloadState
import com.cleartune.core.model.DownloadSummary
import com.cleartune.core.model.LocationType
import com.cleartune.core.model.SourceType
import com.cleartune.core.model.TrackId
import com.cleartune.core.network.WebDavUrlPolicy
import com.cleartune.data.download.DownloadCredentials
import com.cleartune.data.download.DownloadFileLocator
import com.cleartune.data.download.DownloadFilePolicy
import com.cleartune.data.download.DownloadPaths
import com.cleartune.data.download.DownloadPersistencePort
import com.cleartune.data.download.DownloadRecordStore
import com.cleartune.data.download.DownloadWork
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object OfflineDownloadSource {
    val ID = com.cleartune.core.model.SourceId("offline-downloads")

    fun entity() = MusicSourceEntity(
        id = ID.value,
        name = "Offline downloads",
        type = SourceType.LOCAL.name,
        baseUrl = null,
        allowCleartext = false,
        credentialAlias = null,
        enabled = true,
        lastSyncedAtEpochMs = null,
    )
}

class RoomDownloadPersistenceAdapter(
    private val database: ClearTuneDatabase,
    private val credentialStore: CredentialStore,
    private val downloadRoot: File,
    private val clock: () -> Long = System::currentTimeMillis,
) : DownloadPersistencePort, DownloadRecordStore, DownloadFileLocator {
    private val filePolicy = DownloadFilePolicy(downloadRoot)

    init {
        check(downloadRoot.mkdirs() || downloadRoot.isDirectory)
    }

    override fun observe(): Flow<List<DownloadSummary>> = database.downloadDao().observeDownloads()
        .map { downloads -> downloads.map { it.toSummary() } }

    override suspend fun get(id: DownloadId): DownloadSummary? =
        database.downloadDao().download(id.value)?.toSummary()

    override suspend fun findByTrack(trackId: TrackId): DownloadSummary? =
        database.downloadDao().downloadForTrack(trackId.value)?.toSummary()

    override suspend fun insert(summary: DownloadSummary) {
        database.withTransaction {
            check(database.downloadDao().download(summary.id.value) == null) { "Download already exists" }
            val sourceId = requireNotNull(activeRemoteSourceId(summary.trackId)) {
                "Track has no active remote source"
            }
            database.downloadDao().upsert(summary.toEntity(sourceId = sourceId))
        }
    }

    override suspend fun replace(summary: DownloadSummary) {
        database.withTransaction {
            val existing = requireNotNull(database.downloadDao().download(summary.id.value)) { "Download not found" }
            val sourceId = existing.sourceId ?: activeRemoteSourceId(summary.trackId)
            database.downloadDao().upsert(summary.toEntity(existing, sourceId))
        }
    }

    override suspend fun remove(id: DownloadId) {
        database.withTransaction {
            database.downloadDao().delete(id.value)
            database.libraryWriteDao().markLocationUnavailable("download-location-${id.value}")
        }
    }

    override suspend fun loadWork(downloadId: DownloadId): DownloadWork? {
        val resolved = resolve(downloadId) ?: return null
        val base = WebDavUrlPolicy.normalizeBaseUrl(
            requireNotNull(resolved.source.baseUrl),
            resolved.source.allowCleartext,
        )
        val url = resolved.location.uri.toHttpUrlOrNull()
            ?.takeIf { WebDavUrlPolicy.isInBaseSubtree(base, it) }
            ?: return null
        val paths = paths(resolved)
        val alias = resolved.source.credentialAlias
        val credential = if (alias == null) null else credentialStore.get(alias)
        return DownloadWork(
            summary = resolved.download.toSummary(),
            url = url,
            paths = paths,
            expectedBytes = resolved.location.sizeBytes,
            etag = resolved.location.etag,
            credentials = credential?.let {
                DownloadCredentials(it.username, it.password, protectionBase = base)
            },
        )
    }

    override suspend fun markRunning(downloadId: DownloadId) = update(downloadId) { current ->
        current.copy(state = DownloadState.RUNNING.name, errorMessage = null, updatedAtEpochMs = clock())
    }

    override suspend fun beginWork(downloadId: DownloadId): Long? = database.withTransaction {
        val resolved = resolve(downloadId) ?: return@withTransaction null
        val current = resolved.download
        if (current.state !in setOf(DownloadState.QUEUED.name, DownloadState.FAILED.name, DownloadState.RUNNING.name)) {
            return@withTransaction null
        }
        val generation = current.workGeneration + 1
        database.downloadDao().upsert(
            current.copy(
                state = DownloadState.RUNNING.name,
                sourceId = resolved.source.id.value,
                workGeneration = generation,
                errorMessage = null,
                updatedAtEpochMs = clock(),
            ),
        )
        generation
    }

    override suspend fun persistProgress(
        downloadId: DownloadId,
        generation: Long,
        downloadedBytes: Long,
        totalBytes: Long?,
    ): Boolean = updateRunning(downloadId, generation) { current ->
            val monotonicBytes = downloadedBytes.coerceAtLeast(current.bytesDownloaded)
            val safeTotal = totalBytes?.coerceAtLeast(monotonicBytes)
                ?: current.totalBytes?.coerceAtLeast(monotonicBytes)
            current.copy(
                bytesDownloaded = monotonicBytes,
                totalBytes = safeTotal,
                updatedAtEpochMs = clock(),
            )
        }

    override suspend fun publishDownloadedLocation(downloadId: DownloadId, bytes: Long, finalPath: String) {
        publishDownloadedLocation(downloadId, generation = 0, bytes = bytes, finalPath = finalPath)
    }

    override suspend fun publishDownloadedLocation(
        downloadId: DownloadId,
        generation: Long,
        bytes: Long,
        finalPath: String,
    ): Boolean {
        val finalFile = File(finalPath).canonicalFile
        require(finalFile.toPath().startsWith(downloadRoot.canonicalFile.toPath())) {
            "Downloaded file escapes its root"
        }
        require(finalFile.isFile && finalFile.length() == bytes) { "Downloaded file is not complete" }
        var deleteRejectedFile = false
        val published = database.withTransaction {
            val current = database.downloadDao().download(downloadId.value) ?: return@withTransaction false
            if (current.state != DownloadState.RUNNING.name || current.workGeneration != generation) {
                deleteRejectedFile = current.state in setOf(DownloadState.PAUSED.name, DownloadState.CANCELED.name)
                return@withTransaction false
            }
            val resolved = requireNotNull(resolve(downloadId)) { "Download work disappeared" }
            database.sourceDao().upsert(OfflineDownloadSource.entity())
            val completed = resolved.download.copy(
                state = DownloadState.COMPLETED.name,
                bytesDownloaded = bytes,
                totalBytes = bytes,
                partialPath = null,
                finalPath = finalFile.absolutePath,
                errorMessage = null,
                updatedAtEpochMs = clock(),
                sourceId = resolved.source.id.value,
                cleanupPending = false,
            )
            database.downloadDao().upsert(completed)
            database.libraryWriteDao().upsertLocation(
                TrackLocationEntity(
                    id = "download-location-${downloadId.value}",
                    trackId = resolved.trackId,
                    sourceId = OfflineDownloadSource.ID.value,
                    sourceKey = "download:${downloadId.value}",
                    type = LocationType.DOWNLOADED_FILE.name,
                    uri = finalFile.toURI().toString(),
                    available = true,
                    sizeBytes = bytes,
                    etag = resolved.location.etag,
                    relativeFolder = "",
                    displayName = finalFile.name,
                    modifiedEpochSeconds = finalFile.lastModified().coerceAtLeast(0) / 1_000,
                ),
            )
            true
        }
        if (!published && deleteRejectedFile && finalFile.exists() && !finalFile.delete()) {
            throw java.io.IOException("Unable to reconcile rejected download publication")
        }
        return published
    }

    override suspend fun recordFailure(downloadId: DownloadId, generation: Long, code: String): Boolean =
        updateRunning(downloadId, generation) { current ->
        current.copy(
            state = DownloadState.FAILED.name,
            errorMessage = code.take(160),
            updatedAtEpochMs = clock(),
        )
    }

    override fun paths(id: DownloadId): DownloadPaths? = runBlocking(Dispatchers.IO) {
        resolve(id)?.let(::paths)
    }

    private suspend fun resolve(downloadId: DownloadId): ResolvedDownload? {
        val download = database.downloadDao().download(downloadId.value) ?: return null
        val track = database.libraryReadDao().track(download.trackId) ?: return null
        val location = database.libraryReadDao().playableLocations(track.id)
            .firstOrNull { it.type == LocationType.REMOTE_URL.name }
            ?: return null
        val source = database.sourceDao().source(location.sourceId)
            ?.let { entity ->
                com.cleartune.core.model.MusicSource(
                    id = com.cleartune.core.model.SourceId(entity.id),
                    name = entity.name,
                    type = SourceType.valueOf(entity.type),
                    baseUrl = entity.baseUrl,
                    allowCleartext = entity.allowCleartext,
                    credentialAlias = entity.credentialAlias?.let(::CredentialAlias),
                    enabled = entity.enabled,
                    lastSyncedAtEpochMs = entity.lastSyncedAtEpochMs,
                )
            } ?: return null
        return ResolvedDownload(download, track.id, track.title, location, source)
    }

    private fun paths(resolved: ResolvedDownload): DownloadPaths {
        val suggestedName = resolved.location.uri.toHttpUrlOrNull()?.pathSegments?.lastOrNull(String::isNotBlank)
            ?: resolved.title
        return filePolicy.paths(resolved.source.id.value, resolved.trackId, suggestedName)
    }

    private suspend fun update(downloadId: DownloadId, transform: (DownloadEntity) -> DownloadEntity) {
        database.withTransaction {
            val current = requireNotNull(database.downloadDao().download(downloadId.value)) { "Download not found" }
            database.downloadDao().upsert(transform(current))
        }
    }

    private suspend fun updateRunning(
        downloadId: DownloadId,
        generation: Long,
        transform: (DownloadEntity) -> DownloadEntity,
    ): Boolean = database.withTransaction {
        val current = database.downloadDao().download(downloadId.value) ?: return@withTransaction false
        if (current.state != DownloadState.RUNNING.name || current.workGeneration != generation) {
            return@withTransaction false
        }
        database.downloadDao().upsert(transform(current))
        true
    }

    private suspend fun activeRemoteSourceId(trackId: TrackId): String? {
        database.libraryReadDao().playableLocations(trackId.value).forEach { location ->
            if (location.type == LocationType.REMOTE_URL.name &&
                database.sourceDao().source(location.sourceId) != null
            ) return location.sourceId
        }
        return null
    }

    private fun DownloadSummary.toEntity(
        existing: DownloadEntity? = null,
        sourceId: String? = existing?.sourceId,
    ) = DownloadEntity(
        id = id.value,
        trackId = trackId.value,
        state = state.name,
        bytesDownloaded = bytesDownloaded,
        totalBytes = totalBytes,
        etag = existing?.etag,
        partialPath = existing?.partialPath,
        finalPath = finalPath,
        errorMessage = errorMessage,
        updatedAtEpochMs = clock(),
        sourceId = sourceId,
        workGeneration = existing?.workGeneration ?: 0,
        cleanupPending = existing?.cleanupPending ?: false,
    )

    private fun DownloadEntity.toSummary() = DownloadSummary(
        id = DownloadId(id),
        trackId = TrackId(trackId),
        state = runCatching { DownloadState.valueOf(state) }.getOrDefault(DownloadState.FAILED),
        bytesDownloaded = bytesDownloaded.coerceAtLeast(0),
        totalBytes = totalBytes?.coerceAtLeast(bytesDownloaded.coerceAtLeast(0)),
        finalPath = finalPath,
        errorMessage = errorMessage,
    )

    private data class ResolvedDownload(
        val download: DownloadEntity,
        val trackId: String,
        val title: String,
        val location: TrackLocationEntity,
        val source: com.cleartune.core.model.MusicSource,
    )
}
