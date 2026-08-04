package com.cleartune.core.model

data class PlaylistSummary(
    val id: PlaylistId,
    val name: String,
    val trackCount: Int = 0,
) {
    init {
        require(name.isNotBlank())
        require(trackCount >= 0)
    }
}

sealed interface PlaylistCommand {
    data class Create(val name: String) : PlaylistCommand
    data class Rename(val playlistId: PlaylistId, val name: String) : PlaylistCommand
    data class Delete(val playlistId: PlaylistId) : PlaylistCommand
    data class AddTrack(val playlistId: PlaylistId, val trackId: TrackId) : PlaylistCommand
    data class RemoveTrack(val playlistId: PlaylistId, val playlistItemId: PlaylistItemId) : PlaylistCommand
    data class MoveTrack(val playlistId: PlaylistId, val playlistItemId: PlaylistItemId, val newIndex: Int) : PlaylistCommand
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class ReducedMotionMode { SYSTEM, ON, OFF }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val reducedMotionMode: ReducedMotionMode = ReducedMotionMode.SYSTEM,
)

sealed interface SettingsCommand {
    data class SetTheme(val mode: ThemeMode) : SettingsCommand
    data class SetReducedMotion(val mode: ReducedMotionMode) : SettingsCommand
}
