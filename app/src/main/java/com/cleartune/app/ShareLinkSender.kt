package com.cleartune.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import com.cleartune.app.artwork.ArtworkCache
import com.cleartune.app.library.MusicViewModel
import com.cleartune.app.share.MusicShare
import com.cleartune.app.share.ShareKind
import com.cleartune.app.share.shareMessage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Include readable music metadata even when the receiver ignores title/cover extras. */
internal suspend fun sendShareLink(context: Context, share: MusicShare, musicViewModel: MusicViewModel) {
    if (share.url.isBlank()) return
    val thumbnail = try {
        withContext(Dispatchers.IO) { shareThumbnail(context, share, musicViewModel) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
    context.startActivity(Intent.createChooser(shareLinkIntent(context, share, thumbnail), "分享音乐"))
}

internal fun shareLinkIntent(context: Context, share: MusicShare, thumbnail: Uri? = null): Intent {
    val title = when (share.kind) {
        ShareKind.SONG -> "歌曲 · ${share.title}"
        ShareKind.ALBUM -> "专辑 · ${share.title}"
        ShareKind.PLAYLIST -> "歌单 · ${share.title}"
        ShareKind.UNKNOWN -> share.title
    }
    return Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, shareMessage(share))
        putExtra(Intent.EXTRA_TITLE, title)
        putExtra(Intent.EXTRA_SUBJECT, title)
        thumbnail?.let { uri ->
            clipData = ClipData.newUri(context.contentResolver, "音乐封面", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

private suspend fun shareThumbnail(context: Context, share: MusicShare, musicViewModel: MusicViewModel): Uri? {
    val cover = share.coverArtId.displayableArtworkId()?.let { id ->
        val local = ArtworkCache.localFile(context, musicViewModel.artworkAccountKey, id)
        local?.let(::decodeArtwork) ?: musicViewModel.coverArtUrl(id, 256)?.let { url ->
            runCatching { downloadArtwork(url) }.getOrNull()
        }
    }
    val image = cover ?: BitmapFactory.decodeResource(context.resources, clearTuneFallbackCover(share.title))
        ?: return null
    val edge = minOf(image.width, image.height)
    val square = Bitmap.createBitmap(image, (image.width - edge) / 2, (image.height - edge) / 2, edge, edge)
    val thumbnail = Bitmap.createScaledBitmap(square, 256, 256, true)
    val directory = File(context.cacheDir, "share_previews")
    if (!directory.isDirectory && !directory.mkdirs()) return null
    directory.listFiles()?.filter { it.isFile && it.lastModified() < System.currentTimeMillis() - 86_400_000L }
        ?.forEach(File::delete)
    val file = File.createTempFile("cover-", ".jpg", directory)
    var ready = false
    try {
        file.outputStream().use { output ->
            if (!thumbnail.compress(Bitmap.CompressFormat.JPEG, 85, output)) return null
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.sharefileprovider", file)
        ready = true
        return uri
    } finally {
        if (!ready) file.delete()
    }
}

private fun decodeArtwork(file: File): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val sample = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 512)
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}

private fun downloadArtwork(url: String): Bitmap? {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 2_000
        connection.readTimeout = 2_000
        if (connection.responseCode !in 200..299 || connection.contentLengthLong > 3_000_000) return null
        val bytes = connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            while (output.size() <= 3_000_000) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
            }
            if (output.size() > 3_000_000) return null
            output.toByteArray()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 512)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample })
    } finally {
        connection.disconnect()
    }
}
