package com.cleartune.app

import androidx.room.withTransaction
import com.cleartune.core.database.ClearTuneDatabase
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

class RoomWebDavSourceRemovalCoordinator(
    private val database: ClearTuneDatabase,
    downloadRoot: File,
    private val sourceWork: SourceWorkCancellation,
    private val downloadWork: DownloadWorkCancellation,
    private val credentials: CredentialDeletion,
    private val clearCheckpoint: suspend (SourceId) -> Unit = {},
    private val artworkCache: ArtworkCache = ArtworkCache.None,
) {
    private val root = downloadRoot.canonicalFile

    suspend fun remove(sourceId: SourceId, deleteOfflineCopies: Boolean) {
        val source = database.sourceDao().source(sourceId.value)
            ?: database.sourceDao().tombstone(sourceId.value)
            ?: error("Source not found")
        val affected = database.downloadDao().downloadsForSource(sourceId.value)
        sourceWork.cancel(sourceId)
        affected.forEach { downloadWork.stop(DownloadId(it.id)) }

        database.withTransaction {
            database.sourceDao().softDelete(sourceId.value)
            database.libraryWriteDao().markRemoteLocationsUnavailable(sourceId.value)
            if (deleteOfflineCopies) {
                affected.forEach { download ->
                    database.libraryWriteDao()
                        .locationIncludingUnavailable(OfflineDownloadSource.ID.value, "download:${download.id}")
                        ?.let { database.libraryWriteDao().markLocationUnavailable(it.id) }
                    database.downloadDao().upsert(
                        download.copy(
                            state = DownloadState.CANCELED.name,
                            cleanupPending = true,
                            errorMessage = CLEANUP_PENDING_MESSAGE,
                            updatedAtEpochMs = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }

        clearCheckpoint(sourceId)
        clearArtwork(sourceId)
        source.credentialAlias?.let { alias ->
            credentials.delete(CredentialAlias(alias))
            database.sourceDao().clearTombstoneCredential(sourceId.value)
        }
        if (deleteOfflineCopies) reconcilePendingCleanup()
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
            sourceWork.cancel(sourceId)
            clearCheckpoint(sourceId)
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
}
