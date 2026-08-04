package com.cleartune.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class ClearTuneLibrarySessionCallback : MediaLibrarySession.Callback {
    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(
        LibraryResult.ofItem(folder(ROOT_ID, "ClearTune"), params),
    )

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>> {
        val children = if (parentId == ROOT_ID) {
            listOf(
                folder("songs", "歌曲"),
                folder("albums", "专辑"),
                folder("artists", "艺术家"),
                folder("playlists", "歌单"),
                folder("downloads", "已下载"),
            )
        } else {
            emptyList()
        }
        val from = (page.coerceAtLeast(0) * pageSize.coerceAtLeast(1)).coerceAtMost(children.size)
        val to = (from + pageSize.coerceAtLeast(1)).coerceAtMost(children.size)
        return Futures.immediateFuture(LibraryResult.ofItemList(children.subList(from, to), params))
    }

    private fun folder(id: String, title: String): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build(),
        )
        .build()

    private companion object { const val ROOT_ID = "root" }
}
