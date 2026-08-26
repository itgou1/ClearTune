package com.cleartune.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class MobileAudioQuality(val maxBitRate: Int?) {
    RATE_128(128),
    RATE_192(192),
    RATE_320(320),
    ORIGINAL(null),
    ;

    companion object {
        fun fromLegacyBitRate(value: Int?): MobileAudioQuality = when (value) {
            128 -> RATE_128
            320 -> RATE_320
            0 -> ORIGINAL
            else -> RATE_192
        }
    }
}

enum class EqualizerPreset {
    BALANCED,
    CLEAR_VOCAL,
    WARM_BASS,
    AIRY_TREBLE,
    NIGHT_SOFT,
    CUSTOM,
}

data class EqualizerSettings(
    val enabled: Boolean = false,
    val preset: EqualizerPreset = EqualizerPreset.BALANCED,
    val customLevelsDb: List<Int> = DEFAULT_EQUALIZER_LEVELS_DB,
) {
    val activeLevelsDb: List<Int>
        get() = when (preset) {
            EqualizerPreset.BALANCED -> listOf(0, 0, 0, 0, 0)
            EqualizerPreset.CLEAR_VOCAL -> listOf(-2, 0, 3, 2, -1)
            EqualizerPreset.WARM_BASS -> listOf(4, 3, 0, -1, -2)
            EqualizerPreset.AIRY_TREBLE -> listOf(-2, -1, 0, 3, 4)
            EqualizerPreset.NIGHT_SOFT -> listOf(-2, 0, 1, 0, -3)
            EqualizerPreset.CUSTOM -> customLevelsDb.sanitizedEqualizerLevels()
        }
}

val EQUALIZER_FREQUENCIES_HZ = listOf(60, 230, 910, 3_600, 14_000)
val DEFAULT_EQUALIZER_LEVELS_DB = listOf(2, 1, 0, 1, 2)

private fun List<Int>.sanitizedEqualizerLevels(): List<Int> =
    takeIf { size == EQUALIZER_FREQUENCIES_HZ.size }
        ?.map { it.coerceIn(-6, 6) }
        ?: DEFAULT_EQUALIZER_LEVELS_DB

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val volumeNormalizationEnabled: Boolean = true,
    val equalizer: EqualizerSettings = EqualizerSettings(),
    val wifiOnlyDownloads: Boolean = true,
    val mobileAudioQuality: MobileAudioQuality = MobileAudioQuality.RATE_192,
    val checkUpdates: Boolean = true,
    val recentSearches: List<String> = emptyList(),
)

private val Context.appSettingsDataStore by preferencesDataStore(name = "app_settings")

class AppPreferences(private val context: Context) {
    val settings: Flow<AppSettings> = context.appSettingsDataStore.data.map { preferences ->
        AppSettings(
            themeMode = preferences[THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            volumeNormalizationEnabled = preferences[VOLUME_NORMALIZATION] ?: true,
            equalizer = EqualizerSettings(
                enabled = preferences[EQUALIZER_ENABLED] ?: false,
                preset = preferences[EQUALIZER_PRESET]
                    ?.let { runCatching { EqualizerPreset.valueOf(it) }.getOrNull() }
                    ?: EqualizerPreset.BALANCED,
                customLevelsDb = preferences[EQUALIZER_CUSTOM_LEVELS]
                    ?.split(EQUALIZER_SEPARATOR)
                    ?.mapNotNull(String::toIntOrNull)
                    ?.sanitizedEqualizerLevels()
                    ?: DEFAULT_EQUALIZER_LEVELS_DB,
            ),
            wifiOnlyDownloads = preferences[WIFI_ONLY] ?: true,
            mobileAudioQuality = preferences[MOBILE_AUDIO_QUALITY]
                ?.let { runCatching { MobileAudioQuality.valueOf(it) }.getOrNull() }
                ?: MobileAudioQuality.fromLegacyBitRate(preferences[MOBILE_BIT_RATE]),
            checkUpdates = preferences[CHECK_UPDATES] ?: true,
            recentSearches = preferences[RECENT_SEARCHES]
                ?.split(SEARCH_SEPARATOR)
                ?.filter(String::isNotBlank)
                .orEmpty(),
        )
    }

    suspend fun setThemeMode(value: ThemeMode) = edit { it[THEME] = value.name }
    suspend fun setVolumeNormalizationEnabled(value: Boolean) = edit {
        it[VOLUME_NORMALIZATION] = value
    }
    suspend fun setEqualizerEnabled(value: Boolean) = edit { it[EQUALIZER_ENABLED] = value }
    suspend fun setEqualizerPreset(value: EqualizerPreset) = edit { it[EQUALIZER_PRESET] = value.name }
    suspend fun setEqualizerCustomLevels(value: List<Int>) = edit {
        it[EQUALIZER_CUSTOM_LEVELS] = value.sanitizedEqualizerLevels().joinToString(EQUALIZER_SEPARATOR)
    }
    suspend fun setWifiOnlyDownloads(value: Boolean) = edit { it[WIFI_ONLY] = value }
    suspend fun setMobileAudioQuality(value: MobileAudioQuality) = edit {
        it[MOBILE_AUDIO_QUALITY] = value.name
        it.remove(MOBILE_BIT_RATE)
    }
    suspend fun setCheckUpdates(value: Boolean) = edit { it[CHECK_UPDATES] = value }

    suspend fun addRecentSearch(query: String) = edit { preferences ->
        val normalized = query.trim().replace(SEARCH_SEPARATOR, " ")
        if (normalized.isNotBlank()) {
            val current = preferences[RECENT_SEARCHES]
                ?.split(SEARCH_SEPARATOR)
                .orEmpty()
            preferences[RECENT_SEARCHES] = (listOf(normalized) + current)
                .distinctBy { it.lowercase() }
                .take(MAX_RECENT_SEARCHES)
                .joinToString(SEARCH_SEPARATOR)
        }
    }

    suspend fun removeRecentSearch(query: String) = edit { preferences ->
        preferences[RECENT_SEARCHES] = preferences[RECENT_SEARCHES]
            ?.split(SEARCH_SEPARATOR)
            .orEmpty()
            .filterNot { it.equals(query, ignoreCase = true) }
            .joinToString(SEARCH_SEPARATOR)
    }

    suspend fun clearRecentSearches() = edit { it.remove(RECENT_SEARCHES) }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.appSettingsDataStore.edit(block)
    }

    private companion object {
        val THEME = stringPreferencesKey("theme")
        val VOLUME_NORMALIZATION = booleanPreferencesKey("volume_normalization")
        val EQUALIZER_ENABLED = booleanPreferencesKey("equalizer_enabled")
        val EQUALIZER_PRESET = stringPreferencesKey("equalizer_preset")
        val EQUALIZER_CUSTOM_LEVELS = stringPreferencesKey("equalizer_custom_levels")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only_downloads")
        val MOBILE_BIT_RATE = intPreferencesKey("mobile_bit_rate")
        val MOBILE_AUDIO_QUALITY = stringPreferencesKey("mobile_audio_quality")
        val CHECK_UPDATES = booleanPreferencesKey("check_updates")
        val RECENT_SEARCHES = stringPreferencesKey("recent_searches")
        const val SEARCH_SEPARATOR = "\u001F"
        const val EQUALIZER_SEPARATOR = ","
        const val MAX_RECENT_SEARCHES = 8
    }
}
