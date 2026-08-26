package com.cleartune.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SubsonicResponseRoot(
    @SerialName("subsonic-response")
    val response: SubsonicResponseDto,
)

@Serializable
data class SubsonicResponseDto(
    val status: String,
    val version: String = "",
    val type: String = "",
    val serverVersion: String = "",
    val openSubsonic: Boolean = false,
    val error: SubsonicErrorDto? = null,
    val openSubsonicExtensions: List<OpenSubsonicExtensionDto> = emptyList(),
    val albumList2: AlbumListDto? = null,
    val artists: ArtistsDto? = null,
    val artist: ArtistDetailDto? = null,
    val topSongs: TopSongsDto? = null,
    val album: AlbumDetailDto? = null,
    val playlists: PlaylistsDto? = null,
    val musicFolders: MusicFoldersDto? = null,
    val directory: MusicDirectoryDto? = null,
    val playlist: PlaylistDetailDto? = null,
    val searchResult3: SearchResult3Dto? = null,
    val genres: GenresDto? = null,
    val playQueue: PlayQueueDto? = null,
    val starred2: Starred2Dto? = null,
    val lyricsList: LyricsListDto? = null,
    val lyrics: PlainLyricsDto? = null,
)

@Serializable
data class TopSongsDto(
    val song: List<SongDto> = emptyList(),
)

@Serializable
data class SubsonicErrorDto(
    val code: Int,
    val message: String = "",
    val helpUrl: String? = null,
)

@Serializable
data class OpenSubsonicExtensionDto(
    val name: String,
    val versions: List<Int> = emptyList(),
)

@Serializable
data class AlbumListDto(
    val album: List<AlbumDto> = emptyList(),
)

@Serializable
data class ArtistsDto(
    val ignoredArticles: String = "",
    val index: List<ArtistIndexDto> = emptyList(),
)

@Serializable
data class ArtistIndexDto(
    val name: String = "",
    val artist: List<ArtistDto> = emptyList(),
)

@Serializable
data class ArtistDto(
    val id: String,
    val name: String,
    val albumCount: Int = 0,
    val coverArt: String? = null,
    val starred: String? = null,
)

@Serializable
data class ArtistDetailDto(
    val id: String,
    val name: String,
    val albumCount: Int = 0,
    val coverArt: String? = null,
    val starred: String? = null,
    val album: List<AlbumDto> = emptyList(),
)

@Serializable
data class AlbumDto(
    val id: String,
    val name: String,
    val artist: String = "",
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int = 0,
    val duration: Long = 0,
    val created: String? = null,
    val starred: String? = null,
    val year: Int? = null,
)

@Serializable
data class AlbumDetailDto(
    val id: String,
    val name: String,
    val artist: String = "",
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int = 0,
    val duration: Long = 0,
    val created: String? = null,
    val starred: String? = null,
    val year: Int? = null,
    val song: List<SongDto> = emptyList(),
)

@Serializable
data class SongDto(
    val id: String,
    val title: String,
    val isDir: Boolean = false,
    val artist: String = "",
    val artistId: String? = null,
    val album: String = "",
    val albumId: String? = null,
    val duration: Long = 0,
    val track: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val coverArt: String? = null,
    val contentType: String? = null,
    val suffix: String? = null,
    val bitRate: Int? = null,
    val size: Long? = null,
    val playCount: Long = 0,
    val played: String? = null,
    val starred: String? = null,
    val created: String? = null,
    val replayGain: ReplayGainDto? = null,
)

@Serializable
data class ReplayGainDto(
    val trackGain: Double? = null,
    val albumGain: Double? = null,
    val trackPeak: Double? = null,
    val albumPeak: Double? = null,
    val baseGain: Double? = null,
    val fallbackGain: Double? = null,
)

@Serializable
data class MusicFoldersDto(
    val musicFolder: List<MusicFolderDto> = emptyList(),
)

@Serializable
data class MusicFolderDto(
    val id: String,
    val name: String,
)

@Serializable
data class MusicDirectoryDto(
    val id: String = "",
    val name: String = "",
    val child: List<SongDto> = emptyList(),
)

@Serializable
data class PlaylistsDto(
    val playlist: List<PlaylistDto> = emptyList(),
)

@Serializable
data class PlaylistDto(
    val id: String,
    val name: String,
    val songCount: Int = 0,
    val duration: Long = 0,
    val owner: String? = null,
    val public: Boolean = false,
    val coverArt: String? = null,
    val changed: String? = null,
)

@Serializable
data class PlaylistDetailDto(
    val id: String,
    val name: String,
    val songCount: Int = 0,
    val duration: Long = 0,
    val owner: String? = null,
    val public: Boolean = false,
    val coverArt: String? = null,
    val changed: String? = null,
    val entry: List<SongDto> = emptyList(),
)

@Serializable
data class SearchResult3Dto(
    val artist: List<ArtistDto> = emptyList(),
    val album: List<AlbumDto> = emptyList(),
    val song: List<SongDto> = emptyList(),
)

@Serializable
data class GenresDto(
    val genre: List<GenreDto> = emptyList(),
)

@Serializable
data class GenreDto(
    val value: String,
    val songCount: Int = 0,
    val albumCount: Int = 0,
)

@Serializable
data class PlayQueueDto(
    val current: String? = null,
    val position: Long = 0,
    val username: String = "",
    val changed: String? = null,
    val changedBy: String? = null,
    val entry: List<SongDto> = emptyList(),
)

@Serializable
data class Starred2Dto(
    val artist: List<ArtistDto> = emptyList(),
    val album: List<AlbumDto> = emptyList(),
    val song: List<SongDto> = emptyList(),
)

@Serializable
data class LyricsListDto(
    val structuredLyrics: List<StructuredLyricsDto> = emptyList(),
)

@Serializable
data class StructuredLyricsDto(
    val displayArtist: String = "",
    val displayTitle: String = "",
    val lang: String = "",
    val synced: Boolean = false,
    val line: List<LyricLineDto> = emptyList(),
)

@Serializable
data class LyricLineDto(
    val start: Long? = null,
    val value: String = "",
)

@Serializable
data class PlainLyricsDto(
    val artist: String = "",
    val title: String = "",
    val value: String = "",
)
