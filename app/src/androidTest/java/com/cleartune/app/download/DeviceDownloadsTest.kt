package com.cleartune.app.download

import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.cleartune.core.database.DatabaseFactory
import com.cleartune.core.database.DownloadEntity
import com.cleartune.core.database.toEntity
import com.cleartune.core.model.Song
import com.cleartune.core.model.accountStorageKey
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DeviceDownloadsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun publicPublicationIsVerifiedIndexedAndIndependentOfLogin() = runBlocking<Unit> {
        val store = DeviceDownloads(context)
        val account = accountStorageKey("https://test.example", UUID.randomUUID().toString())
        val id = UUID.randomUUID().toString()
        val source = File(context.cacheDir, "$id.wav").apply { writeBytes(ByteArray(4096) { it.toByte() }) }
        try {
            val item = store.publish(id, account, Song("one", "same name", suffix = "wav"), source)
            assertEquals("content", Uri.parse(item.uri).scheme)
            assertTrue(store.list().any { it.id == id && !it.legacy })
            assertEquals(item.uri, store.find(account, "one")!!.uri)
            assertNull(store.find(accountStorageKey("https://test.example", "other"), "one"))
            context.contentResolver.query(Uri.parse(item.uri), arrayOf(MediaStore.Audio.Media.RELATIVE_PATH,
                MediaStore.Audio.Media.IS_PENDING), null, null, null)!!.use {
                assertTrue(it.moveToFirst()); assertEquals("Music/ClearTune/", it.getString(0)); assertEquals(0, it.getInt(1))
            }
            assertEquals(item.uri, store.publish(id, account, item.song, source).uri)
            assertArrayEquals(source.readBytes(), context.contentResolver.openInputStream(Uri.parse(item.uri))!!.use { it.readBytes() })
            store.remove(item, false)
            assertTrue(store.readable(item.uri))
            assertNull(store.find(account, "one"))
            context.contentResolver.delete(Uri.parse(item.uri), null, null)
        } finally {
            store.indexed(id)?.let { store.remove(it, store.readable(it.uri)) }
            source.delete(); context.deleteDatabase("cleartune_account_$account.db")
        }
    }

    @Test fun legacyAccountMigrationPublishesBeforeRemovingOriginalAndCanRetry() = runBlocking<Unit> {
        val store = DeviceDownloads(context)
        val account = accountStorageKey("https://legacy.example", UUID.randomUUID().toString())
        val id = UUID.randomUUID().toString()
        val folder = File(context.filesDir, "accounts/$account/offline_music").apply { mkdirs() }
        val source = File(folder, "$id.wav").apply { writeBytes(ByteArray(2048) { 42 }) }
        val partial = File(folder, "orphan.part").apply { writeBytes(byteArrayOf(1, 2)) }
        val db = DatabaseFactory.create(context, account)
        try {
            val song = Song("legacy-song", "Old song", suffix = "wav")
            db.mediaDao().upsertSongs(listOf(song.toEntity()))
            db.downloadDao().upsert(DownloadEntity(id, song.id, "COMPLETED", source.length(), source.length(),
                Uri.fromFile(source).toString(), null, System.currentTimeMillis()))
            val legacy = store.list().first { it.id == id && it.legacy }
            assertTrue(legacy.complete)
            assertFalse(store.list().first { it.uri == Uri.fromFile(partial).toString() }.complete)
            // Simulate interruption after public index commit but before private cleanup.
            val published = store.publish(id, account, song, source)
            assertTrue(source.exists())
            store.migrate(legacy)
            assertFalse(source.exists())
            assertEquals(published.uri, db.downloadDao().forRequest(id)!!.localUri)
            assertEquals(1, store.list().count { it.id == id })
            context.contentResolver.delete(Uri.parse(published.uri), null, null)
            assertNull(store.find(account, song.id))
            assertFalse(store.list().first { it.id == id }.available)
        } finally {
            store.indexed(id)?.let { store.remove(it, store.readable(it.uri)) }
            db.close(); context.deleteDatabase("cleartune_account_$account.db")
            folder.parentFile!!.deleteRecursively()
        }
    }
}
