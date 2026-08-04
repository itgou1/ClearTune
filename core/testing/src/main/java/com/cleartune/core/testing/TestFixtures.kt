package com.cleartune.core.testing

import com.cleartune.core.model.AlbumId
import com.cleartune.core.model.ArtistId
import com.cleartune.core.model.DownloadId
import com.cleartune.core.model.LocationId
import com.cleartune.core.model.PlaylistId
import com.cleartune.core.model.PlaylistItemId
import com.cleartune.core.model.QueueItemId
import com.cleartune.core.model.SourceId
import com.cleartune.core.model.TrackId

object TestIds {
    val track = TrackId("track-1")
    val source = SourceId("source-1")
    val location = LocationId("location-1")
    val album = AlbumId("album-1")
    val artist = ArtistId("artist-1")
    val playlist = PlaylistId("playlist-1")
    val playlistItem = PlaylistItemId("playlist-item-1")
    val download = DownloadId("download-1")
    val queueItem = QueueItemId("queue-item-1")
}
