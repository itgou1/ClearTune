package com.cleartune.app.artwork

import android.content.Context
import android.graphics.BitmapFactory
import coil3.imageLoader
import com.cleartune.core.model.ServerCredentials
import com.cleartune.core.model.accountStorageKey
import com.cleartune.core.network.OpenSubsonicApiFactory
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** UI cache is disposable; offline artwork lives beside its account's downloaded music. */
object ArtworkCache {
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()
    private val clears = AtomicLong()
    val generation: Long get() = clears.get()
    private val offlineWrites = Mutex()
    private val urls = linkedMapOf<String, String>()

    @Synchronized
    fun url(key: String, create: () -> String): String {
        urls[key]?.let { return it }
        if (urls.size >= 2_048) urls.clear()
        return create().also { urls[key] = it }
    }

    @Synchronized
    fun clearUrls() { urls.clear() }

    internal fun offlineFile(context: Context, account: String, id: String): File {
        require(account.matches(Regex("[a-f0-9]{64}")))
        return File(context.filesDir, "accounts/$account/offline_artwork/${artworkFileName(id)}")
    }

    fun localFile(context: Context, account: String, id: String): File? =
        offlineFile(context, account, id).takeIf { it.isFile && it.length() > 0 }

    /** One 768px copy serves every UI size and the offline media notification. */
    suspend fun saveOffline(
        context: Context,
        credentials: ServerCredentials,
        id: String,
        checkActive: suspend () -> Unit,
    ) = withContext(Dispatchers.IO) {
        offlineWrites.withLock {
            checkActive()
            val account = accountStorageKey(credentials.baseUrl, credentials.username)
            val target = offlineFile(context, account, id)
            if (localFile(context, account, id) != null) return@withLock
            val directory = target.parentFile!!
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create artwork directory")
            val temporary = File(directory, "${UUID.randomUUID()}.part")
            val connection = URL(OpenSubsonicApiFactory().authorized(credentials).coverArtUrl(id, 768))
                .openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                if (connection.responseCode !in 200..299) throw IOException("Artwork request failed")
                connection.inputStream.use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var bytes = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            checkActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            bytes += count
                            if (bytes > 10 * 1_024 * 1_024) throw IOException("Artwork is too large")
                            output.write(buffer, 0, count)
                        }
                    }
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(temporary.path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Invalid artwork")
                checkActive()
                if (!temporary.renameTo(target)) throw IOException("Cannot save artwork")
                changes.update { it + 1 }
            } finally {
                connection.disconnect()
                temporary.delete()
            }
        }
    }

    /** Query references under the same lock as publication, keeping shared album covers alive. */
    suspend fun pruneOffline(context: Context, account: String, referencedIds: suspend () -> Set<String>) =
        withContext(Dispatchers.IO) {
            offlineWrites.withLock {
                val names = referencedIds().map(::artworkFileName).toSet()
                val directory = offlineFile(context, account, "").parentFile!!
                directory.listFiles()?.filter { it.extension == "img" && it.name !in names }
                    ?.forEach { it.delete() }
                changes.update { it + 1 }
            }
        }

    suspend fun clearTemporary(context: Context): Boolean = withContext(Dispatchers.IO) {
        val loader = context.imageLoader
        var success = true
        loader.memoryCache?.clear()
        try { loader.diskCache?.clear() } catch (_: Exception) { success = false }
        clearUrls()
        clears.incrementAndGet()
        // Never delete an active image cache's directory/index behind the cache library.
        val managed = loader.diskCache?.directory?.toFile()?.canonicalFile
        val root = context.cacheDir.canonicalFile
        root.listFiles()?.forEach { child ->
            val path = child.canonicalFile
            val containsManaged = managed != null && managed.toPath().startsWith(path.toPath())
            if (path.parentFile == root && !containsManaged && !child.deleteRecursively()) success = false
        }
        changes.update { it + 1 }
        success
    }
}
