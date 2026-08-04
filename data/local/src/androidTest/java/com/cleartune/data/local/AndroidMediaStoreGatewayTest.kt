package com.cleartune.data.local

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidMediaStoreGatewayTest {
    @Test
    fun query_uses_scoped_content_uri_and_skips_one_malformed_row_with_warning() = runBlocking {
        val resolver = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver

        val result = AndroidMediaStoreGateway(
            contentResolver = resolver,
            sdkInt = 37,
            collectionUri = TEST_AUDIO_URI,
        ).readAudio()

        assertEquals(1, result.snapshots.size)
        assertEquals("content://media/external/audio/media/42", result.snapshots.single().contentUri)
        assertEquals("Music/Albums", result.snapshots.single().relativeFolder)
        assertEquals(1, result.warnings.size)
        assertTrue(result.warnings.single().contains("43"))
    }
}

class AudioRowsProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        require(selection == "${MediaStore.Audio.Media.IS_MUSIC} != 0")
        val columns = requireNotNull(projection)
        return MatrixCursor(columns).apply {
            addRow(columns.map { column -> goodValue(column) }.toTypedArray())
            addRow(columns.map { column -> malformedValue(column) }.toTypedArray())
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = null

    private fun goodValue(column: String): Any? = when (column) {
        MediaStore.Audio.Media._ID -> 42L
        MediaStore.Audio.Media.DISPLAY_NAME -> "Song.FLAC"
        MediaStore.Audio.Media.RELATIVE_PATH -> "Music\\Albums\\"
        MediaStore.Audio.Media.TITLE -> "Song"
        MediaStore.Audio.Media.ALBUM -> "Album"
        MediaStore.Audio.Media.ARTIST -> "Artist"
        MediaStore.Audio.Media.DURATION -> 1_000L
        MediaStore.Audio.Media.SIZE -> 10L
        MediaStore.Audio.Media.DATE_MODIFIED -> 1L
        MediaStore.Audio.Media.MIME_TYPE -> "audio/flac"
        else -> null
    }

    private fun malformedValue(column: String): Any? = when (column) {
        MediaStore.Audio.Media._ID -> 43L
        MediaStore.Audio.Media.DISPLAY_NAME -> "notes.txt"
        MediaStore.Audio.Media.RELATIVE_PATH -> "Downloads/"
        MediaStore.Audio.Media.TITLE -> "Notes"
        MediaStore.Audio.Media.DURATION -> 1L
        MediaStore.Audio.Media.SIZE -> 1L
        MediaStore.Audio.Media.DATE_MODIFIED -> 1L
        MediaStore.Audio.Media.MIME_TYPE -> "text/plain"
        else -> null
    }
}

private val TEST_AUDIO_URI = Uri.parse("content://com.cleartune.data.local.test.audio/audio")
