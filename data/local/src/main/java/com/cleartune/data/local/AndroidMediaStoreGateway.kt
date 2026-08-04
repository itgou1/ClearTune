package com.cleartune.data.local

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidMediaStoreGateway(
    private val contentResolver: ContentResolver,
    private val mapper: MediaStoreRowMapper = MediaStoreRowMapper(),
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val collectionUri: Uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
) : MediaStoreGateway {
    override suspend fun readAudio(): MediaStoreReadResult = withContext(Dispatchers.IO) {
        val snapshots = mutableListOf<LocalAudioSnapshot>()
        val warnings = mutableListOf<String>()
        val observedSourceKeys = linkedSetOf<String>()
        var isComplete = true
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.DATE_MODIFIED)
            add(MediaStore.Audio.Media.MIME_TYPE)
            if (sdkInt >= 29) add(MediaStore.Audio.Media.RELATIVE_PATH) else add(MediaStore.Audio.Media.DATA)
        }.toTypedArray()
        val cursor = contentResolver.query(
            collectionUri,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.Audio.Media.DATE_MODIFIED} DESC",
        ) ?: return@withContext MediaStoreReadResult(
            snapshots = emptyList(),
            warnings = listOf("MediaStore query returned no cursor"),
            isComplete = false,
        )
        cursor.use {
            while (cursor.moveToNext()) {
                try {
                    val row = MediaStoreRow(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)),
                        displayName = cursor.stringOrNull(MediaStore.Audio.Media.DISPLAY_NAME),
                        relativePath = if (sdkInt >= 29) cursor.stringOrNull(MediaStore.Audio.Media.RELATIVE_PATH) else null,
                        dataPath = if (sdkInt < 29) cursor.stringOrNull(MediaStore.Audio.Media.DATA) else null,
                        title = cursor.stringOrNull(MediaStore.Audio.Media.TITLE),
                        album = cursor.stringOrNull(MediaStore.Audio.Media.ALBUM),
                        artist = cursor.stringOrNull(MediaStore.Audio.Media.ARTIST),
                        durationMs = cursor.longOrNull(MediaStore.Audio.Media.DURATION),
                        sizeBytes = cursor.longOrNull(MediaStore.Audio.Media.SIZE) ?: 0,
                        modifiedEpochSeconds = cursor.longOrNull(MediaStore.Audio.Media.DATE_MODIFIED) ?: 0,
                        mimeType = cursor.stringOrNull(MediaStore.Audio.Media.MIME_TYPE),
                    )
                    mapper.map(row)?.let { snapshot ->
                        snapshots.add(snapshot)
                        observedSourceKeys.add(snapshot.sourceKey)
                    } ?: run {
                        if (mapper.shouldRetainPreviousOnMappingFailure(row)) {
                            observedSourceKeys.add("mediastore:${row.id}")
                        }
                        warnings.add("Skipped unsupported or malformed row: ${row.id}")
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: RuntimeException) {
                    isComplete = false
                    warnings.add("Skipped malformed MediaStore row: ${failure.javaClass.simpleName}")
                }
            }
        }
        MediaStoreReadResult(snapshots, warnings, observedSourceKeys, isComplete)
    }
}

private fun android.database.Cursor.stringOrNull(column: String): String? = getColumnIndex(column)
    .takeIf { it >= 0 && !isNull(it) }
    ?.let(::getString)

private fun android.database.Cursor.longOrNull(column: String): Long? = getColumnIndex(column)
    .takeIf { it >= 0 && !isNull(it) }
    ?.let(::getLong)
