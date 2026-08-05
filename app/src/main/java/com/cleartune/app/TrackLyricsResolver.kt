package com.cleartune.app

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import com.cleartune.core.contracts.PlaybackLibraryRepository
import com.cleartune.core.contracts.SourceRepository
import com.cleartune.core.model.LocationType
import com.cleartune.core.model.TrackId
import com.cleartune.core.model.TrackLocation
import com.cleartune.data.webdav.OkHttpWebDavClient
import com.cleartune.feature.player.LrcParser
import com.cleartune.feature.player.LyricsUiState
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun interface LyricsSidecarReader {
    suspend fun read(location: TrackLocation): ByteArray?
}

class TrackLyricsResolver(
    private val library: PlaybackLibraryRepository,
    private val sidecars: LyricsSidecarReader,
    private val parser: LrcParser = LrcParser(),
) {
    suspend fun resolve(trackId: TrackId): LyricsUiState = runCatching {
        val track = library.getPlayableTrack(trackId) ?: return LyricsUiState.Unavailable
        val ordered = track.locations.filter(TrackLocation::available).sortedBy { location ->
            when (location.type) {
                LocationType.LOCAL_URI -> 0
                LocationType.DOWNLOADED_FILE -> 1
                LocationType.REMOTE_URL -> 2
            }
        }
        ordered.forEach { location ->
            val lines = sidecars.read(location)?.let(parser::parse).orEmpty()
            if (lines.isNotEmpty()) return LyricsUiState.Available(lines)
        }
        LyricsUiState.Unavailable
    }.getOrDefault(LyricsUiState.Unavailable)
}

class ProductionLyricsSidecarReader(
    private val contentResolver: ContentResolver,
    private val sources: SourceRepository,
    private val webDavClient: OkHttpWebDavClient,
    private val maximumBytes: Int = 256 * 1024,
) : LyricsSidecarReader {
    init { require(maximumBytes > 0) }

    override suspend fun read(location: TrackLocation): ByteArray? = when (location.type) {
        LocationType.LOCAL_URI -> readLocalContent(Uri.parse(location.uri), location.sourceKey)
        LocationType.DOWNLOADED_FILE -> readFile(Uri.parse(location.uri))
        LocationType.REMOTE_URL -> readWebDav(location)
    }

    private suspend fun readWebDav(location: TrackLocation): ByteArray? {
        val source = sources.getSource(location.sourceId) ?: return null
        val trackUrl = location.uri.toHttpUrlOrNull() ?: return null
        val fileName = trackUrl.pathSegments.lastOrNull()?.substringBeforeLast('.', trackUrl.pathSegments.last())
            ?.plus(".lrc") ?: return null
        val sidecar = trackUrl.newBuilder().removePathSegment(trackUrl.pathSize - 1).addPathSegment(fileName).build()
        return webDavClient.readRange(source, sidecar, 0, maximumBytes.toLong() - 1, maximumBytes).bytes
    }

    private suspend fun readLocalContent(uri: Uri, sourceKey: String): ByteArray? = withContext(Dispatchers.IO) {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return@withContext readFile(uri)
        val metadata = contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) null else {
                val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
                val pathIndex = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                val path = if (pathIndex >= 0 && !cursor.isNull(pathIndex)) cursor.getString(pathIndex) else null
                name to path
            }
        } ?: return@withContext null
        val relativePath = metadata.second ?: return@withContext null
        val sidecarName = metadata.first.substringBeforeLast('.', metadata.first) + ".lrc"
        val collection = MediaStore.Files.getContentUri("external")
        val sidecarUri = contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(relativePath, sidecarName),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) Uri.withAppendedPath(collection, cursor.getLong(0).toString()) else null
        } ?: return@withContext null
        contentResolver.openInputStream(sidecarUri)?.use { it.readBounded(maximumBytes) }
    }

    private suspend fun readFile(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        val rawPath = if (uri.scheme == ContentResolver.SCHEME_FILE) uri.path else uri.toString()
        val audio = rawPath?.let(::File)?.canonicalFile ?: return@withContext null
        val sidecar = File(audio.parentFile ?: return@withContext null, audio.nameWithoutExtension + ".lrc").canonicalFile
        if (sidecar.parentFile != audio.parentFile || !sidecar.isFile || sidecar.length() > maximumBytes) return@withContext null
        sidecar.inputStream().use { it.readBounded(maximumBytes) }
    }
}

private fun java.io.InputStream.readBounded(maximumBytes: Int): ByteArray? {
    val output = ByteArrayOutputStream(minOf(maximumBytes, 8 * 1024))
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        if (total > maximumBytes) return null
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
