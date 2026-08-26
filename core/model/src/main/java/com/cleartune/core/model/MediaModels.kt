package com.cleartune.core.model

data class Artist(
    val id: String,
    val name: String,
    val albumCount: Int = 0,
    val coverArtId: String? = null,
    val starredAt: Long? = null,
)

data class Album(
    val id: String,
    val name: String,
    val artistId: String? = null,
    val artistName: String = "未知艺术家",
    val year: Int? = null,
    val songCount: Int = 0,
    val durationSeconds: Long = 0,
    val coverArtId: String? = null,
    val starredAt: Long? = null,
    val createdAt: Long? = null,
)

data class ReplayGain(
    val trackGainDb: Double? = null,
    val albumGainDb: Double? = null,
    val trackPeak: Double? = null,
    val albumPeak: Double? = null,
    val baseGainDb: Double? = null,
    val fallbackGainDb: Double? = null,
)

data class Song(
    val id: String,
    val title: String,
    val artistId: String? = null,
    val artistName: String = "未知艺术家",
    val albumId: String? = null,
    val albumName: String = "未知专辑",
    val durationSeconds: Long = 0,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val coverArtId: String? = null,
    val contentType: String? = null,
    val suffix: String? = null,
    val bitRate: Int? = null,
    val sizeBytes: Long? = null,
    val playCount: Long = 0,
    val lastPlayedAt: Long? = null,
    val starredAt: Long? = null,
    val createdAt: Long? = null,
    val replayGain: ReplayGain? = null,
)

data class Playlist(
    val id: String,
    val name: String,
    val songCount: Int = 0,
    val durationSeconds: Long = 0,
    val owner: String? = null,
    val public: Boolean = false,
    val coverArtId: String? = null,
    val changedAt: Long? = null,
)

data class MusicFolder(
    val id: String,
    val name: String,
)

data class MusicDirectory(
    val folders: List<MusicFolder>,
    val songs: List<Song>,
)

data class LyricLine(
    val startMs: Long? = null,
    val text: String,
)

data class Lyrics(
    val songId: String,
    val synced: Boolean,
    val lines: List<LyricLine>,
)

enum class PlaybackMode {
    SEQUENTIAL,
    REPEAT_ALL,
    REPEAT_ONE,
    SHUFFLE,
}

enum class DownloadState {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    FAILED,
    COMPLETED,
}

data class DownloadItem(
    val requestId: String,
    val songId: String,
    val state: DownloadState,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long? = null,
    val localUri: String? = null,
    val failureReason: String? = null,
) {
    val progress: Float
        get() = totalBytes?.takeIf { it > 0 }?.let { bytesDownloaded.toFloat() / it } ?: 0f
}

object DownloadStateMachine {
    fun canTransition(from: DownloadState, to: DownloadState): Boolean = when (from) {
        DownloadState.QUEUED -> to in setOf(DownloadState.DOWNLOADING, DownloadState.PAUSED, DownloadState.FAILED)
        DownloadState.DOWNLOADING -> to in setOf(DownloadState.PAUSED, DownloadState.FAILED, DownloadState.COMPLETED)
        DownloadState.PAUSED -> to in setOf(DownloadState.QUEUED, DownloadState.FAILED)
        DownloadState.FAILED -> to == DownloadState.QUEUED
        DownloadState.COMPLETED -> false
    }
}

enum class PlayEventType {
    STARTED,
    COMPLETED,
    SKIPPED,
    REPLAYED,
}
