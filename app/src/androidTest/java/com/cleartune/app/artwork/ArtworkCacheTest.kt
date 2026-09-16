package com.cleartune.app.artwork

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.asImage
import coil3.imageLoader
import coil3.memory.MemoryCache
import com.cleartune.core.model.accountStorageKey
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArtworkCacheTest {
    @Test fun clearRemovesMemoryDiskAndUrlsButPreservesOfflineArtwork() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val account = accountStorageKey("https://${UUID.randomUUID()}.example", "alice")
        val other = accountStorageKey("https://${UUID.randomUUID()}.example", "bob")
        val cover = "shared-cover"
        val offline = ArtworkCache.offlineFile(context, account, cover)
        try {
            offline.parentFile!!.mkdirs()
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            offline.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertEquals(offline, ArtworkCache.localFile(context, account, cover))
            assertNull(ArtworkCache.localFile(context, other, cover))
            val loader = context.imageLoader
            val key = artworkCacheKey(account, cover, 192)
            val memoryKey = MemoryCache.Key(key)
            loader.memoryCache!![memoryKey] = MemoryCache.Value(bitmap.asImage())
            loader.diskCache!!.openEditor(key)!!.let { editor ->
                editor.metadata.toFile().writeText("")
                editor.data.toFile().writeBytes(offline.readBytes())
                editor.commit()
            }
            loader.diskCache!!.openSnapshot(key).let { snapshot ->
                assertNotNull(snapshot)
                snapshot?.close()
            }
            ArtworkCache.url(key) { "old-url" }
            assertTrue(ArtworkCache.clearTemporary(context))
            assertNull(loader.memoryCache!![memoryKey])
            assertNull(loader.diskCache!!.openSnapshot(key))
            assertEquals("new-url", ArtworkCache.url(key) { "new-url" })
            assertTrue(offline.isFile)
            assertEquals(offline, ArtworkCache.localFile(context, account, cover))
        } finally { File(context.filesDir, "accounts/$account").deleteRecursively() }
    }

    @Test fun pruningPreservesReferencedAlbumAndOtherAccount() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val server = "https://${UUID.randomUUID()}.example"
        val alice = accountStorageKey(server, "alice")
        val bob = accountStorageKey(server, "bob")
        try {
            val shared = ArtworkCache.offlineFile(context, alice, "shared")
            val unused = ArtworkCache.offlineFile(context, alice, "unused")
            val private = ArtworkCache.offlineFile(context, bob, "unused")
            listOf(shared, unused, private).forEach { it.parentFile!!.mkdirs(); it.writeBytes(byteArrayOf(1)) }
            ArtworkCache.pruneOffline(context, alice) { setOf("shared") }
            assertTrue(shared.exists())
            assertFalse(unused.exists())
            assertTrue(private.exists())
            ArtworkCache.pruneOffline(context, alice) { emptySet() }
            assertFalse(shared.exists())
            assertTrue(private.exists())
        } finally {
            listOf(alice, bob).forEach { File(context.filesDir, "accounts/$it").deleteRecursively() }
        }
    }
}
