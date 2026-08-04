package com.cleartune.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

data class LibraryCatalogTrack(
    val mediaId: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val artworkUri: String? = null,
    val playbackUri: String,
    val mimeType: String? = null,
)

interface LibrarySessionCatalog {
    fun children(parentId: String): List<LibraryCatalogTrack>
    fun resolve(mediaId: String): LibraryCatalogTrack?

    data object Empty : LibrarySessionCatalog {
        override fun children(parentId: String) = emptyList<LibraryCatalogTrack>()
        override fun resolve(mediaId: String): LibraryCatalogTrack? = null
    }
}

data class SessionMediaDescription(
    val mediaId: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val artworkUri: String? = null,
    val playbackUri: String? = null,
    val mimeType: String? = null,
    val browsable: Boolean = false,
    val playable: Boolean = false,
)

class ClearTuneLibrarySessionCallback(
    private val catalog: LibrarySessionCatalog = LibrarySessionCatalog.Empty,
) : MediaLibrarySession.Callback {
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
    ): ListenableFuture<LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>> =
        Futures.immediateFuture(
            LibraryResult.ofItemList(
                describeChildren(parentId, page, pageSize).map(::toMediaItem),
                params,
            ),
        )

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val item = catalog.resolve(mediaId)
            ?.let { describeTrack(it, includePlayback = false) }
            ?.let(::toMediaItem)
        return Futures.immediateFuture(
            item?.let { LibraryResult.ofItem(it, null) }
                ?: LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE),
        )
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<List<MediaItem>> = Futures.immediateFuture(
        describeForPlayback(mediaItems.map { it.mediaId }).map(::toMediaItem),
    )

    fun describeChildren(parentId: String, page: Int, pageSize: Int): List<SessionMediaDescription> {
        val children = when (parentId) {
            ROOT_ID -> ROOT_CATEGORIES.map { (id, title) ->
                SessionMediaDescription(id, title, browsable = true)
            }
            in APPROVED_CATEGORY_IDS -> catalog.children(parentId).map { describeTrack(it, includePlayback = false) }
            else -> emptyList()
        }
        val safePageSize = pageSize.coerceAtLeast(1)
        val from = (page.coerceAtLeast(0) * safePageSize).coerceAtMost(children.size)
        val to = (from + safePageSize).coerceAtMost(children.size)
        return children.subList(from, to)
    }

    fun describeForPlayback(mediaIds: List<String>): List<SessionMediaDescription> = mediaIds
        .mapNotNull(catalog::resolve)
        .map { describeTrack(it, includePlayback = true) }

    private fun describeTrack(
        track: LibraryCatalogTrack,
        includePlayback: Boolean,
    ): SessionMediaDescription {
        val metadata = MediaItemFactory.sanitizeMetadata(
            track.title,
            track.artist,
            track.album,
            track.artworkUri,
        )
        return SessionMediaDescription(
            mediaId = track.mediaId,
            title = metadata.title,
            artist = metadata.artist,
            album = metadata.album,
            artworkUri = metadata.artworkUri,
            playbackUri = track.playbackUri.takeIf { includePlayback }?.let { playbackUri ->
                PrivateMediaSourceRegistry.register(track.mediaId, playbackUri)
            },
            mimeType = track.mimeType,
            playable = true,
        )
    }

    private fun toMediaItem(description: SessionMediaDescription): MediaItem = MediaItem.Builder()
        .setMediaId(description.mediaId)
        .apply {
            description.playbackUri?.let(::setUri)
            description.mimeType?.let(::setMimeType)
        }
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(description.title)
                .setArtist(description.artist)
                .setAlbumTitle(description.album)
                .setIsBrowsable(description.browsable)
                .setIsPlayable(description.playable)
                .setMediaType(
                    if (description.browsable) MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                    else MediaMetadata.MEDIA_TYPE_MUSIC,
                )
                .apply { description.artworkUri?.let { setArtworkUri(Uri.parse(it)) } }
                .build(),
        )
        .build()

    private fun folder(id: String, title: String): MediaItem = toMediaItem(
        SessionMediaDescription(mediaId = id, title = title, browsable = true),
    )

    private companion object {
        const val ROOT_ID = "root"
        val ROOT_CATEGORIES = listOf(
            "songs" to "Songs",
            "albums" to "Albums",
            "artists" to "Artists",
            "playlists" to "Playlists",
            "downloads" to "Downloads",
        )
        val APPROVED_CATEGORY_IDS = ROOT_CATEGORIES.mapTo(mutableSetOf()) { it.first }
    }
}
