package com.cleartune.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cleartune.core.model.PlaybackMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.playbackDataStore by preferencesDataStore(name = "playback_preferences")

class PlaybackPreferences(private val context: Context) {
    val mode: Flow<PlaybackMode> = context.playbackDataStore.data.map { preferences ->
        preferences[MODE]?.let { runCatching { PlaybackMode.valueOf(it) }.getOrNull() }
            ?: PlaybackMode.SEQUENTIAL
    }

    suspend fun setMode(mode: PlaybackMode) {
        context.playbackDataStore.edit { it[MODE] = mode.name }
    }

    private companion object {
        val MODE = stringPreferencesKey("playback_mode")
    }
}
