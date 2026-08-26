package com.cleartune.core.database

import com.cleartune.core.model.Album
import com.cleartune.core.model.Artist
import com.cleartune.core.model.Playlist
import com.cleartune.core.model.ReplayGain
import com.cleartune.core.model.Song

fun Artist.toEntity(now: Long = System.currentTimeMillis()) = ArtistEntity(
    id = id,
    name = name,
    albumCount = albumCount,
    coverArtId = coverArtId,
    starredAt = starredAt,
    updatedAt = now,
)

fun ArtistEntity.toModel() = Artist(id, name, albumCount, coverArtId, starredAt)

fun Album.toEntity(now: Long = System.currentTimeMillis()) = AlbumEntity(
    id = id,
    name = name,
    artistId = artistId,
    artistName = artistName,
    year = year,
    songCount = songCount,
    durationSeconds = durationSeconds,
    coverArtId = coverArtId,
    starredAt = starredAt,
    createdAt = createdAt,
    updatedAt = now,
)

fun AlbumEntity.toModel() = Album(
    id,
    name,
    artistId,
    artistName,
    year,
    songCount,
    durationSeconds,
    coverArtId,
    starredAt,
    createdAt,
)

fun Song.toEntity(now: Long = System.currentTimeMillis()) = SongEntity(
    id,
    title,
    artistId,
    artistName,
    albumId,
    albumName,
    durationSeconds,
    trackNumber,
    discNumber,
    year,
    genre,
    coverArtId,
    contentType,
    suffix,
    bitRate,
    sizeBytes,
    playCount,
    lastPlayedAt,
    starredAt,
    createdAt,
    replayGain?.trackGainDb,
    replayGain?.albumGainDb,
    replayGain?.trackPeak,
    replayGain?.albumPeak,
    replayGain?.baseGainDb,
    replayGain?.fallbackGainDb,
    now,
)

fun SongEntity.toModel() = Song(
    id,
    title,
    artistId,
    artistName,
    albumId,
    albumName,
    durationSeconds,
    trackNumber,
    discNumber,
    year,
    genre,
    coverArtId,
    contentType,
    suffix,
    bitRate,
    sizeBytes,
    playCount,
    lastPlayedAt,
    starredAt,
    createdAt,
    replayGain = if (
        replayGainTrackDb != null || replayGainAlbumDb != null ||
        replayGainTrackPeak != null || replayGainAlbumPeak != null ||
        replayGainBaseDb != null || replayGainFallbackDb != null
    ) {
        ReplayGain(
            trackGainDb = replayGainTrackDb,
            albumGainDb = replayGainAlbumDb,
            trackPeak = replayGainTrackPeak,
            albumPeak = replayGainAlbumPeak,
            baseGainDb = replayGainBaseDb,
            fallbackGainDb = replayGainFallbackDb,
        )
    } else {
        null
    },
)

fun Playlist.toEntity(now: Long = System.currentTimeMillis()) = PlaylistEntity(
    id,
    name,
    songCount,
    durationSeconds,
    owner,
    public,
    coverArtId,
    changedAt,
    now,
)

fun PlaylistEntity.toModel() = Playlist(
    id,
    name,
    songCount,
    durationSeconds,
    owner,
    isPublic,
    coverArtId,
    changedAt,
)
