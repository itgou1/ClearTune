package com.cleartune.app

import androidx.room.withTransaction
import com.cleartune.core.database.ClearTuneDatabase
import com.cleartune.core.database.entity.DownloadEntity
import com.cleartune.core.database.entity.MusicSourceEntity
import com.cleartune.core.model.CredentialAlias
import com.cleartune.core.model.DownloadId
import com.cleartune.core.model.DownloadState
import com.cleartune.core.model.SourceId
import com.cleartune.data.webdav.ArtworkCache
import java.io.File
import kotlinx.coroutines.CancellationException

fun interface SourceWorkCancellation {
    suspend fun cancel(sourceId: SourceId)
}

interface DownloadWorkCancellation {
    suspend fun stop(downloadId: DownloadId)
    suspend fun deleteFile(file: File): Boolean
}

fun interface CredentialDeletion {
    suspend fun delete(alias: CredentialAlias)
}

internal suspend fun <T> commitSourceRemoval(
    transaction: suspend () -> T,
    afterCommit: suspend (T) -> Unit,
): T {
    val committed = transaction()
    afterCommit(committed)
    return committed
}

class RoomWebDavSourceRemovalCoordinator(
    private val database: ClearTuneDatabase,
    downloadRoot: File,
    private val sourceWork: SourceWorkCancellation,
    private val downloadWork: DownloadWorkCancellation,
    private val credentials: CredentialDeletion,
    private val retireCheckpoint: suspend (SourceId) -> Unit = {},
    private val artworkCache: ArtworkCache = ArtworkCache.None,
) {
    private val root = downloadRoot.canonicalFile

    suspend fun remove(sourceId: SourceId, deleteOfflineCopies: Boolean) {
        val committed = commitSourceRemoval(
            transaction = {
                database.withTransaction {
                    val source = database.sourceDao().source(sourceId.value)
                        ?: database.sourceDao().tombstone(sourceId.value)
                        ?: error("Source not found")
                    database.sourceDao().softDelete(sourceId.value)
                    database.libraryWriteDao().markRemoteLocationsUnavailable(sourceId.value)
                    val affected = database.downloadDao().downloadsForSource(sourceId.value)
                    affected.forEach { download ->
                        markDownloadRemoved(download, deleteOfflineCopies)
                    }
                    CommittedRemoval(source, affected.map { DownloadId(it.id) })
                }
            },
            afterCommit = { result ->
                sourceWork.cancel(sourceId)
                result.downloadIds.forEach { downloadWork.stop(it) }
            },
        )

        retireCheckpoint(sourceId)
        clearArtwork(sourceId)
        committed.source.credentialAlias?.let { alias ->
            credentials.delete(CredentialAlias(alias))
            database.sourceDao().clearTombstoneCredential(sourceId.value)
        }
        if (deleteOfflineCopies) reconcilePendingCleanup()
    }

    private suspend fun markDownloadRemoved(download: DownloadEntity, deleteOfflineCopies: Boolean) {
        if (deleteOfflineCopies) {
            database.libraryWriteDao()
                .locationIncludingUnavailable(OfflineDownloadSource.ID.value, "download:${download.id}")
                ?.let { database.libraryWriteDao().markLocationUnavailable(it.id) }
        }
        val cleanupPending = deleteOfflineCopies || download.cleanupPending
        val retainedCompleted = !cleanupPending && download.state in setOf(
            DownloadState.COMPLETED.name,
            DownloadState.UPDATE_AVAILABLE.name,
        )
        database.downloadDao().upsert(
            download.copy(
                state = if (retainedCompleted) DownloadState.COMPLETED.name else DownloadState.CANCELED.name,
                cleanupPending = cleanupPending,
                errorMessage = if (cleanupPending) CLEANUP_PENDING_MESSAGE else null,
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun committedDownloadsForTombstone(sourceId: SourceId): List<DownloadId> =
        database.withTransaction {
            database.libraryWriteDao().markRemoteLocationsUnavailable(sourceId.value)
            val affected = database.downloadDao().downloadsForSource(sourceId.value)
            affected.forEach { download ->
                val repairedState = when (download.state) {
                    DownloadState.COMPLETED.name, DownloadState.CANCELED.name -> null
                    DownloadState.UPDATE_AVAILABLE.name -> DownloadState.COMPLETED.name
                    else -> DownloadState.CANCELED.name
                }
                if (repairedState != null) {
                    database.downloadDao().upsert(
                        download.copy(
                            state = repairedState,
                            errorMessage = null,
                            updatedAtEpochMs = System.currentTimeMillis(),
                        ),
                    )
                }
            }
            affected.map { DownloadId(it.id) }
        }

    suspend fun reconcilePendingCleanup() {
        database.downloadDao().pendingCleanup().forEach { download ->
            val files = listOfNotNull(download.partialPath, download.finalPath)
                .map(::File)
                .distinctBy { it.absolutePath }
            val safelyContained = files.all { file ->
                runCatching { file.canonicalFile.toPath().startsWith(root.toPath()) }.getOrDefault(false)
            }
            if (!safelyContained) return@forEach
            val cleaned = try {
                files.all { file -> downloadWork.deleteFile(file.canonicalFile) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                false
            }
            if (!cleaned) return@forEach
            database.withTransaction {
                val current = database.downloadDao().download(download.id) ?: return@withTransaction
                if (!current.cleanupPending) return@withTransaction
                database.downloadDao().upsert(
                    current.copy(
                        bytesDownloaded = 0,
                        totalBytes = null,
                        partialPath = null,
                        finalPath = null,
                        cleanupPending = false,
                        errorMessage = null,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    suspend fun reconcile() {
        database.sourceDao().tombstones().forEach { source ->
            val sourceId = SourceId(source.id)
            val downloadIds = committedDownloadsForTombstone(sourceId)
            sourceWork.cancel(sourceId)
            downloadIds.forEach { downloadWork.stop(it) }
            retireCheckpoint(sourceId)
            clearArtwork(sourceId)
            source.credentialAlias?.let { alias ->
                credentials.delete(CredentialAlias(alias))
                database.sourceDao().clearTombstoneCredential(source.id)
            }
        }
        reconcilePendingCleanup()
    }

    private suspend fun clearArtwork(sourceId: SourceId) {
        val prefix = artworkCache.sourceUriPrefix(sourceId) ?: return
        artworkCache.clearSource(sourceId)
        database.withTransaction {
            database.libraryWriteDao().clearTrackArtworkRefsWithPrefix(prefix)
            database.libraryWriteDao().clearAlbumArtworkRefsWithPrefix(prefix)
        }
    }

    private companion object {
        const val CLEANUP_PENDING_MESSAGE = "Offline cleanup pending"
    }

    private data class CommittedRemoval(
        val source: MusicSourceEntity,
        val downloadIds: List<DownloadId>,
    )
}
