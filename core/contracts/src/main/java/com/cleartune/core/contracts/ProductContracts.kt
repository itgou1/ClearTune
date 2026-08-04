package com.cleartune.core.contracts

import com.cleartune.core.model.AppSettings
import com.cleartune.core.model.PlaylistCommand
import com.cleartune.core.model.PlaylistSummary
import com.cleartune.core.model.SettingsCommand
import kotlinx.coroutines.flow.Flow

interface PlaylistRepository {
    fun observePlaylists(): Flow<List<PlaylistSummary>>
    suspend fun apply(command: PlaylistCommand)
}

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun update(command: SettingsCommand)
}
