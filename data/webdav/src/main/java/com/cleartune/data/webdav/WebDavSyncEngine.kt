package com.cleartune.data.webdav

import com.cleartune.core.contracts.LibraryWriteGateway
import com.cleartune.core.model.LibraryMutation
import com.cleartune.core.model.LocationId
import com.cleartune.core.model.LocationType
import com.cleartune.core.model.MusicSource
import com.cleartune.core.model.Track
import com.cleartune.core.model.TrackId
import com.cleartune.core.model.TrackLocation
import com.cleartune.core.network.WebDavUrlPolicy
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrl

data class SyncFailure(
    val relativeDirectory: String,
    val retryable: Boolean = false,
)

data class WebDavSyncReport(
    val discoveredTracks: Int,
    val visitedDirectories: Int,
    val failures: List<SyncFailure>,
)

class WebDavSyncEngine(
    private val client: DirectoryListingClient,
    private val libraryWriteGateway: LibraryWriteGateway,
    private val supportedExtensions: Set<String> = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav"),
    private val fingerprintLookup: RemoteFingerprintLookup = RemoteFingerprintLookup { _, _ -> null },
    private val metadataEnricher: WebDavMetadataEnricher = WebDavMetadataEnricher { _, entry ->
        EnrichedTrackMetadata(entry.name.substringBeforeLast('.', entry.name))
    },
    private val updatePublisher: RemoteUpdatePublisher = RemoteUpdatePublisher { _, _ -> },
    private val maxEnrichmentConcurrency: Int = 4,
) {
    init {
        require(maxEnrichmentConcurrency > 0)
    }

    suspend fun sync(
        source: MusicSource,
        checkpoint: WebDavSyncCheckpoint? = null,
        saveCheckpoint: suspend (WebDavSyncCheckpoint) -> Unit = {},
    ): WebDavSyncReport {
        val base = WebDavUrlPolicy.normalizeBaseUrl(requireNotNull(source.baseUrl), source.allowCleartext)
        require(checkpoint == null || checkpoint.sourceId == source.id)
        val queue = ArrayDeque<okhttp3.HttpUrl>().apply {
            val pending = checkpoint?.pendingDirectories ?: listOf(base.toString())
            pending.map { it.toHttpUrl() }.forEach { directory ->
                require(WebDavUrlPolicy.isInBaseSubtree(base, directory))
                add(directory)
            }
        }
        val visited = checkpoint?.visitedDirectories?.toMutableSet() ?: linkedSetOf()
        val retained = checkpoint?.retainedSourceKeys?.toMutableSet() ?: linkedSetOf()
        val failures = checkpoint?.failures?.toMutableList() ?: mutableListOf()
        var discovered = checkpoint?.discoveredTracks ?: 0

        while (queue.isNotEmpty()) {
            val directory = queue.removeFirst()
            if (!visited.add(directory.toString())) continue
            val entries = try {
                client.list(source, directory)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                val relativeDirectory = directory.relativeDirectory()
                val retryable = (failure as? WebDavProtocolException)?.failure?.retryable == true ||
                    failure is java.io.IOException
                failures.removeAll { it.relativeDirectory == relativeDirectory }
                failures += SyncFailure(relativeDirectory, retryable)
                if (retryable) {
                    visited.remove(directory.toString())
                    queue.addFirst(directory)
                }
                saveCheckpoint(checkpoint(source.id, queue, visited, retained, discovered, failures))
                if (retryable) break
                continue
            }
            failures.removeAll { it.relativeDirectory == directory.relativeDirectory() }
            entries.filter { it.isDirectory }.forEach { entry ->
                if (WebDavUrlPolicy.isInBaseSubtree(base, entry.href) && entry.href.toString() !in visited) {
                    queue.addLast(entry.href)
                }
            }
            val audio = entries.filter { !it.isDirectory && it.extension() in supportedExtensions }
            retained += audio.map { it.relativeKey(base) }
            val changed = audio.mapNotNull { entry ->
                val sourceKey = entry.relativeKey(base)
                val previous = fingerprintLookup.find(source.id, sourceKey)
                if (previous?.available == true &&
                    previous.sizeBytes == entry.sizeBytes && previous.etag == entry.etag
                ) return@mapNotNull null
                entry to (previous != null)
            }
            if (changed.isNotEmpty()) {
                val enriched = enrich(source, changed.map { it.first })
                val tracks = changed.mapIndexed { index, (entry, _) ->
                    val sourceKey = entry.relativeKey(base)
                    Track(
                        id = TrackId(stableId("track", source.id.value, sourceKey)),
                        title = enriched[index].title,
                        durationMs = enriched[index].durationMs,
                        artworkRef = enriched[index].artworkRef,
                        albumTitle = enriched[index].albumTitle,
                        artistNames = enriched[index].artistNames,
                        artworkResolved = enriched[index].artworkResolved,
                    )
                }
                val locations = changed.mapIndexed { index, (entry, _) ->
                    val sourceKey = entry.relativeKey(base)
                    TrackLocation(
                        id = LocationId(stableId("location", source.id.value, sourceKey)),
                        trackId = tracks[index].id,
                        sourceId = source.id,
                        sourceKey = sourceKey,
                        type = LocationType.REMOTE_URL,
                        uri = entry.href.toString(),
                        sizeBytes = entry.sizeBytes,
                        etag = entry.etag,
                    )
                }
                discovered += tracks.size
                libraryWriteGateway.applyLibraryMutation(LibraryMutation.Upsert(source.id, tracks, locations))
                changed.filter { it.second }.forEach { (entry, _) ->
                    updatePublisher.markUpdateAvailable(source.id, entry.relativeKey(base))
                }
            }
            saveCheckpoint(checkpoint(source.id, queue, visited, retained, discovered, failures))
        }
        if (failures.isEmpty()) {
            libraryWriteGateway.applyLibraryMutation(LibraryMutation.RetainSourceKeys(source.id, retained))
        }
        return WebDavSyncReport(discovered, visited.size, failures)
    }

    private suspend fun enrich(source: MusicSource, entries: List<WebDavEntry>): List<EnrichedTrackMetadata> = coroutineScope {
        val semaphore = Semaphore(maxEnrichmentConcurrency)
        entries.map { entry ->
            async {
                semaphore.withPermit {
                    try {
                        metadataEnricher.enrich(source, entry)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        EnrichedTrackMetadata(entry.name.substringBeforeLast('.', entry.name))
                    }
                }
            }
        }.awaitAll()
    }

    private fun checkpoint(
        sourceId: com.cleartune.core.model.SourceId,
        queue: ArrayDeque<okhttp3.HttpUrl>,
        visited: Set<String>,
        retained: Set<String>,
        discovered: Int,
        failures: List<SyncFailure>,
    ) = WebDavSyncCheckpoint(
        sourceId = sourceId,
        pendingDirectories = queue.map(okhttp3.HttpUrl::toString),
        visitedDirectories = visited.toSet(),
        retainedSourceKeys = retained.toSet(),
        discoveredTracks = discovered,
        failures = failures.toList(),
    )
}

data class EnrichedTrackMetadata(
    val title: String,
    val albumTitle: String? = null,
    val artistNames: List<String> = emptyList(),
    val durationMs: Long? = null,
    val artworkRef: String? = null,
    val artworkResolved: Boolean = false,
)

fun interface RemoteFingerprintLookup {
    suspend fun find(sourceId: com.cleartune.core.model.SourceId, sourceKey: String): RemoteFingerprint?
}

fun interface WebDavMetadataEnricher {
    suspend fun enrich(source: MusicSource, entry: WebDavEntry): EnrichedTrackMetadata
}

fun interface RemoteUpdatePublisher {
    suspend fun markUpdateAvailable(sourceId: com.cleartune.core.model.SourceId, sourceKey: String)
}

private fun WebDavEntry.extension(): String = name.substringAfterLast('.', "").lowercase()

private fun okhttp3.HttpUrl.relativeDirectory(): String =
    pathSegments.lastOrNull { it.isNotBlank() } ?: "/"

private fun WebDavEntry.relativeKey(base: okhttp3.HttpUrl): String =
    href.encodedPath.removePrefix(base.encodedPath).trimStart('/')

private fun stableId(kind: String, sourceId: String, sourceKey: String): String =
    UUID.nameUUIDFromBytes("$kind:$sourceId:$sourceKey".toByteArray(StandardCharsets.UTF_8)).toString()
