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

data class SyncFailure(val relativeDirectory: String)

data class WebDavSyncReport(
    val discoveredTracks: Int,
    val visitedDirectories: Int,
    val failures: List<SyncFailure>,
)

class WebDavSyncEngine(
    private val client: DirectoryListingClient,
    private val libraryWriteGateway: LibraryWriteGateway,
    private val supportedExtensions: Set<String> = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav"),
) {
    suspend fun sync(source: MusicSource): WebDavSyncReport {
        val base = WebDavUrlPolicy.normalizeBaseUrl(requireNotNull(source.baseUrl), source.allowCleartext)
        val queue = ArrayDeque<okhttp3.HttpUrl>().apply { add(base) }
        val visited = linkedSetOf<String>()
        val retained = linkedSetOf<String>()
        val failures = mutableListOf<SyncFailure>()
        var discovered = 0

        while (queue.isNotEmpty()) {
            val directory = queue.removeFirst()
            if (!visited.add(directory.toString())) continue
            val entries = try {
                client.list(source, directory)
            } catch (_: Exception) {
                failures += SyncFailure(directory.pathSegments.lastOrNull { it.isNotBlank() } ?: "/")
                continue
            }
            entries.filter { it.isDirectory }.forEach { entry ->
                if (WebDavUrlPolicy.isInBaseSubtree(base, entry.href) && entry.href.toString() !in visited) {
                    queue.addLast(entry.href)
                }
            }
            val audio = entries.filter { !it.isDirectory && it.extension() in supportedExtensions }
            if (audio.isNotEmpty()) {
                val tracks = audio.map { entry ->
                    val sourceKey = entry.relativeKey(base)
                    Track(TrackId(stableId("track", source.id.value, sourceKey)), entry.name.substringBeforeLast('.', entry.name))
                }
                val locations = audio.mapIndexed { index, entry ->
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
                retained += audio.map { it.relativeKey(base) }
                discovered += tracks.size
                libraryWriteGateway.applyLibraryMutation(LibraryMutation.Upsert(source.id, tracks, locations))
            }
        }
        if (failures.isEmpty()) {
            libraryWriteGateway.applyLibraryMutation(LibraryMutation.RetainSourceKeys(source.id, retained))
        }
        return WebDavSyncReport(discovered, visited.size, failures)
    }
}

private fun WebDavEntry.extension(): String = name.substringAfterLast('.', "").lowercase()

private fun WebDavEntry.relativeKey(base: okhttp3.HttpUrl): String =
    href.encodedPath.removePrefix(base.encodedPath).trimStart('/')

private fun stableId(kind: String, sourceId: String, sourceKey: String): String =
    UUID.nameUUIDFromBytes("$kind:$sourceId:$sourceKey".toByteArray(StandardCharsets.UTF_8)).toString()
