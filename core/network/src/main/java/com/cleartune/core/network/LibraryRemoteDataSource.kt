package com.cleartune.core.network

import com.cleartune.core.model.Album
import com.cleartune.core.model.Artist
import com.cleartune.core.model.ClearTuneError
import com.cleartune.core.model.Playlist
import com.cleartune.core.model.ReplayGain
import com.cleartune.core.model.Song
import com.cleartune.core.model.Lyrics
import com.cleartune.core.model.LyricLine
import com.cleartune.core.model.MusicDirectory
import com.cleartune.core.model.MusicFolder
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import java.time.OffsetDateTime
import retrofit2.Response

data class SearchResults(
    val artists: List<Artist>,
    val albums: List<Album>,
    val songs: List<Song>,
    val playlists: List<Playlist> = emptyList(),
)

data class AlbumDetails(
    val album: Album,
    val songs: List<Song>,
)

data class ArtistDetails(
    val artist: Artist,
    val albums: List<Album>,
)

data class PlaylistDetails(
    val playlist: Playlist,
    val songs: List<Song>,
)

data class RemotePlayQueue(
    val songs: List<Song>,
    val currentId: String?,
    val positionMs: Long,
    val changedAt: Long?,
)

data class FavoriteResults(
    val songs: List<Song>,
    val albums: List<Album>,
    val artists: List<Artist>,
)

enum class FavoriteTargetType { SONG, ALBUM, ARTIST }

sealed interface RemoteResult<out T> {
    data class Success<T>(val value: T) : RemoteResult<T>
    data class Failure(val error: ClearTuneError) : RemoteResult<Nothing>
}

class LibraryRemoteDataSource(
    private val authorized: AuthorizedOpenSubsonicApi,
) {
    suspend fun albums(type: String = "alphabeticalByName", size: Int = 500, offset: Int = 0) =
        execute { api.getAlbumList2(type, size, offset, authQuery()) }
            .map { it.albumList2?.album.orEmpty().map(AlbumDto::toModel) }

    suspend fun artists() = execute { api.getArtists(authQuery()) }
        .map { response ->
            response.artists?.index.orEmpty().flatMap { index -> index.artist }.map(ArtistDto::toModel)
        }

    suspend fun artist(id: String) = execute { api.getArtist(id, authQuery()) }
        .map { response ->
            val dto = requireNotNull(response.artist)
            ArtistDetails(
                artist = Artist(
                    id = dto.id,
                    name = dto.name,
                    albumCount = dto.albumCount,
                    coverArtId = dto.coverArt,
                    starredAt = parseTimestamp(dto.starred),
                ),
                albums = dto.album.map(AlbumDto::toModel),
            )
        }

    suspend fun topSongs(artist: String, count: Int = 50) =
        execute { api.getTopSongs(artist, count, authQuery()) }
            .map { response -> response.topSongs?.song.orEmpty().map(SongDto::toModel) }

    suspend fun album(id: String) = execute { api.getAlbum(id, authQuery()) }
        .map { response ->
            val dto = requireNotNull(response.album)
            AlbumDetails(
                album = dto.toModel(),
                songs = dto.song.map(SongDto::toModel),
            )
        }

    suspend fun playlists() = execute { api.getPlaylists(authQuery()) }
        .map { it.playlists?.playlist.orEmpty().map(PlaylistDto::toModel) }

    suspend fun musicFolders() = execute { api.getMusicFolders(authQuery()) }
        .map { response ->
            response.musicFolders?.musicFolder.orEmpty().map { MusicFolder(it.id, it.name) }
        }

    suspend fun musicDirectory(id: String) = execute { api.getMusicDirectory(id, authQuery()) }
        .map { response ->
            val children = response.directory?.child.orEmpty()
            MusicDirectory(
                folders = children.filter(SongDto::isDir).map { MusicFolder(it.id, it.title) },
                songs = children.filterNot(SongDto::isDir).map(SongDto::toModel),
            )
        }

    suspend fun playlist(id: String) = execute { api.getPlaylist(id, authQuery()) }
        .map { response ->
            val dto = requireNotNull(response.playlist)
            PlaylistDetails(
                playlist = dto.toModel(),
                songs = dto.entry.map(SongDto::toModel),
            )
        }

    suspend fun search(
        query: String,
        artistCount: Int = 30,
        artistOffset: Int = 0,
        albumCount: Int = 30,
        albumOffset: Int = 0,
        songCount: Int = 100,
        songOffset: Int = 0,
    ) = execute {
        api.search3(
            query = query,
            artistCount = artistCount,
            artistOffset = artistOffset,
            albumCount = albumCount,
            albumOffset = albumOffset,
            songCount = songCount,
            songOffset = songOffset,
            auth = authQuery(),
        )
    }
        .map { response ->
            val result = response.searchResult3
            SearchResults(
                artists = result?.artist.orEmpty().map(ArtistDto::toModel),
                albums = result?.album.orEmpty().map(AlbumDto::toModel),
                songs = result?.song.orEmpty().map(SongDto::toModel),
            )
        }

    suspend fun songs(size: Int = 500, offset: Int = 0) =
        search(
            query = "",
            artistCount = 0,
            albumCount = 0,
            songCount = size,
            songOffset = offset,
        ).map(SearchResults::songs)

    suspend fun genres() = execute { api.getGenres(authQuery()) }
        .map { it.genres?.genre.orEmpty().map(GenreDto::value) }

    suspend fun saveQueue(ids: List<String>, current: String?, positionMs: Long) =
        execute { api.savePlayQueue(ids, current, positionMs, authQuery()) }.map { Unit }

    suspend fun playQueue() = execute { api.getPlayQueue(authQuery()) }.map { response ->
        val queue = requireNotNull(response.playQueue)
        RemotePlayQueue(
            songs = queue.entry.map(SongDto::toModel),
            currentId = queue.current,
            positionMs = queue.position,
            changedAt = parseTimestamp(queue.changed),
        )
    }

    suspend fun scrobble(songId: String, time: Long, submission: Boolean) =
        execute { api.scrobble(songId, time, submission, authQuery()) }.map { Unit }

    suspend fun favorites() = execute { api.getStarred2(authQuery()) }.map { response ->
        val starred = response.starred2 ?: Starred2Dto()
        FavoriteResults(
            songs = starred.song.map(SongDto::toModel),
            albums = starred.album.map(AlbumDto::toModel),
            artists = starred.artist.map(ArtistDto::toModel),
        )
    }

    suspend fun setFavorite(type: FavoriteTargetType, id: String, favorite: Boolean) = execute {
        val songId = id.takeIf { type == FavoriteTargetType.SONG }
        val albumId = id.takeIf { type == FavoriteTargetType.ALBUM }
        val artistId = id.takeIf { type == FavoriteTargetType.ARTIST }
        if (favorite) {
            api.star(songId, albumId, artistId, authQuery())
        } else {
            api.unstar(songId, albumId, artistId, authQuery())
        }
    }.map { Unit }

    suspend fun lyrics(song: Song): RemoteResult<Lyrics> {
        val structured = execute { api.getLyricsBySongId(song.id, authQuery()) }.map { response ->
            val lyrics = response.lyricsList?.structuredLyrics.orEmpty()
                .firstOrNull { it.line.isNotEmpty() }
                ?: error("没有结构化歌词")
            Lyrics(
                songId = song.id,
                synced = lyrics.synced,
                lines = lyrics.line.map { LyricLine(it.start, it.value) },
            )
        }
        if (structured is RemoteResult.Success) return structured
        return execute { api.getLyrics(song.artistName, song.title, authQuery()) }.map { response ->
            val value = response.lyrics?.value.orEmpty()
            Lyrics(
                songId = song.id,
                synced = false,
                lines = value.lines().filter(String::isNotBlank).map { LyricLine(text = it) },
            )
        }
    }

    suspend fun createPlaylist(name: String, songIds: List<String> = emptyList()) =
        execute { api.createPlaylist(name, songIds, authQuery()) }.map { Unit }

    suspend fun renamePlaylist(id: String, name: String) =
        execute { api.updatePlaylist(id, name, emptyList(), emptyList(), authQuery()) }.map { Unit }

    suspend fun addPlaylistSongs(id: String, songIds: List<String>) =
        execute { api.updatePlaylist(id, null, songIds, emptyList(), authQuery()) }.map { Unit }

    suspend fun removePlaylistSongs(id: String, indexes: List<Int>) =
        execute { api.updatePlaylist(id, null, emptyList(), indexes, authQuery()) }.map { Unit }

    suspend fun deletePlaylist(id: String) =
        execute { api.deletePlaylist(id, authQuery()) }.map { Unit }

    fun coverArtUrl(id: String, size: Int = 512): String = authorized.coverArtUrl(id, size)

    fun streamUrl(id: String, maxBitRate: Int? = null, format: String? = null): String =
        authorized.streamUrl(id, maxBitRate, format)

    private suspend fun execute(
        request: suspend AuthorizedOpenSubsonicApi.() -> Response<SubsonicResponseRoot>,
    ): RemoteResult<SubsonicResponseDto> {
        return try {
            val response = authorized.request()
            if (!response.isSuccessful) {
                return RemoteResult.Failure(
                    if (response.code() == 401 || response.code() == 403) {
                        ClearTuneError.Authentication()
                    } else {
                        ClearTuneError.Server(response.code())
                    },
                )
            }
            val body = response.body()?.response
                ?: return RemoteResult.Failure(ClearTuneError.Server())
            if (body.status != "ok") {
                val error = if (body.error?.code in setOf(40, 41, 50)) {
                    ClearTuneError.Authentication()
                } else {
                    ClearTuneError.Server(body.error?.code)
                }
                RemoteResult.Failure(error)
            } else {
                RemoteResult.Success(body)
            }
        } catch (error: SocketTimeoutException) {
            RemoteResult.Failure(ClearTuneError.Timeout(cause = error))
        } catch (error: IOException) {
            RemoteResult.Failure(ClearTuneError.Unreachable(cause = error))
        } catch (error: Exception) {
            RemoteResult.Failure(ClearTuneError.Unexpected(cause = error))
        }
    }
}

private inline fun <T, R> RemoteResult<T>.map(transform: (T) -> R): RemoteResult<R> {
    return when (this) {
        is RemoteResult.Success -> runCatching { transform(value) }
            .fold(
                onSuccess = { RemoteResult.Success(it) },
                onFailure = { RemoteResult.Failure(ClearTuneError.Unexpected(cause = it)) },
            )
        is RemoteResult.Failure -> this
    }
}

private fun ArtistDto.toModel() = Artist(
    id = id,
    name = name,
    albumCount = albumCount,
    coverArtId = coverArt,
    starredAt = parseTimestamp(starred),
)

private fun AlbumDto.toModel() = Album(
    id = id,
    name = name,
    artistId = artistId,
    artistName = artist.ifBlank { "未知艺术家" },
    year = year,
    songCount = songCount,
    durationSeconds = duration,
    coverArtId = coverArt,
    starredAt = parseTimestamp(starred),
    createdAt = parseTimestamp(created),
)

private fun AlbumDetailDto.toModel() = Album(
    id = id,
    name = name,
    artistId = artistId,
    artistName = artist.ifBlank { "未知艺术家" },
    year = year,
    songCount = songCount,
    durationSeconds = duration,
    coverArtId = coverArt,
    starredAt = parseTimestamp(starred),
    createdAt = parseTimestamp(created),
)

private fun SongDto.toModel() = Song(
    id = id,
    title = title,
    artistId = artistId,
    artistName = artist.ifBlank { "未知艺术家" },
    albumId = albumId,
    albumName = album.ifBlank { "未知专辑" },
    durationSeconds = duration,
    trackNumber = track,
    discNumber = discNumber,
    year = year,
    genre = genre,
    coverArtId = coverArt,
    contentType = contentType,
    suffix = suffix,
    bitRate = bitRate,
    sizeBytes = size,
    playCount = playCount,
    lastPlayedAt = parseTimestamp(played),
    starredAt = parseTimestamp(starred),
    createdAt = parseTimestamp(created),
    replayGain = replayGain?.let {
        ReplayGain(
            trackGainDb = it.trackGain,
            albumGainDb = it.albumGain,
            trackPeak = it.trackPeak,
            albumPeak = it.albumPeak,
            baseGainDb = it.baseGain,
            fallbackGainDb = it.fallbackGain,
        )
    },
)

private fun PlaylistDto.toModel() = Playlist(
    id = id,
    name = name,
    songCount = songCount,
    durationSeconds = duration,
    owner = owner,
    public = public,
    coverArtId = coverArt,
    changedAt = parseTimestamp(changed),
)

private fun PlaylistDetailDto.toModel() = Playlist(
    id = id,
    name = name,
    songCount = songCount,
    durationSeconds = duration,
    owner = owner,
    public = public,
    coverArtId = coverArt,
    changedAt = parseTimestamp(changed),
)

private fun parseTimestamp(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    return runCatching { Instant.parse(value).toEpochMilli() }
        .recoverCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
        .getOrNull()
}
