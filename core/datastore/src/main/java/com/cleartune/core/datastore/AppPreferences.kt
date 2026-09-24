package com.cleartune.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
            EqualizerPreset.CLEAR_VOCAL -> listOf(-4, -3, -1, 0, -1)
            EqualizerPreset.WARM_BASS -> listOf(0, 0, -3, -2, -1)
            EqualizerPreset.AIRY_TREBLE -> listOf(-3, -2, -2, -1, 0)
            EqualizerPreset.NIGHT_SOFT -> listOf(-4, -2, 0, -2, -4)
            EqualizerPreset.CUSTOM -> customLevelsDb.sanitizedEqualizerLevels()
        }
}

val EQUALIZER_FREQUENCIES_HZ = listOf(60, 230, 910, 3_600, 14_000)
val DEFAULT_EQUALIZER_LEVELS_DB = listOf(2, 1, 0, 1, 2)
const val DEFAULT_PLAYBACK_CACHE_SIZE_MB = 512
val PLAYBACK_CACHE_SIZE_OPTIONS_MB = listOf(512, 1_024, 2_048, 4_096)

fun normalizedPlaybackCacheSizeMb(value: Int?): Int =
    value?.takeIf(PLAYBACK_CACHE_SIZE_OPTIONS_MB::contains) ?: DEFAULT_PLAYBACK_CACHE_SIZE_MB

private fun List<Int>.sanitizedEqualizerLevels(): List<Int> =
    takeIf { size == EQUALIZER_FREQUENCIES_HZ.size }
        ?.map { it.coerceIn(-6, 6) }
        ?: DEFAULT_EQUALIZER_LEVELS_DB

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val volumeNormalizationEnabled: Boolean = true,
    val equalizer: EqualizerSettings = EqualizerSettings(),
    val wifiOnlyDownloads: Boolean = true,
    val playbackCacheSizeMb: Int = DEFAULT_PLAYBACK_CACHE_SIZE_MB,
    val mobileAudioQuality: MobileAudioQuality = MobileAudioQuality.RATE_192,
    val checkUpdates: Boolean = true,
    val favoriteSongSort: String = DEFAULT_FAVORITE_SONG_SORT,
)

const val DEFAULT_FAVORITE_SONG_SORT = "TITLE"

private val Context.appSettingsDataStore by preferencesDataStore(name = "app_settings")

class AppPreferences(private val context: Context) {
    suspend fun consumeSongBatchHint(): Boolean {
        var shouldShow = false
        edit { preferences ->
            if (preferences[SONG_BATCH_HINT_SHOWN] != true) {
                preferences[SONG_BATCH_HINT_SHOWN] = true
                shouldShow = true
            }
        }
        return shouldShow
    }
    fun accountLibrarySettings(accountKey: String): Flow<AccountLibrarySettings> =
        context.appSettingsDataStore.data.map { preferences ->
            AccountLibrarySettings(
                recentSearches = preferences[recentSearchesKey(accountKey)]
                    ?.split(SEARCH_SEPARATOR)?.filter(String::isNotBlank).orEmpty(),
                lastLibrarySyncEpochMs = preferences[lastLibrarySyncKey(accountKey)] ?: 0L,
            )
        }

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
            playbackCacheSizeMb = normalizedPlaybackCacheSizeMb(preferences[PLAYBACK_CACHE_SIZE_MB]),
            mobileAudioQuality = preferences[MOBILE_AUDIO_QUALITY]
                ?.let { runCatching { MobileAudioQuality.valueOf(it) }.getOrNull() }
                ?: MobileAudioQuality.fromLegacyBitRate(preferences[MOBILE_BIT_RATE]),
            checkUpdates = preferences[CHECK_UPDATES] ?: true,
            favoriteSongSort = preferences[FAVORITE_SONG_SORT] ?: DEFAULT_FAVORITE_SONG_SORT,
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
    suspend fun setPlaybackCacheSizeMb(value: Int) = edit {
        it[PLAYBACK_CACHE_SIZE_MB] = normalizedPlaybackCacheSizeMb(value)
    }
    suspend fun setMobileAudioQuality(value: MobileAudioQuality) = edit {
        it[MOBILE_AUDIO_QUALITY] = value.name
        it.remove(MOBILE_BIT_RATE)
    }

    suspend fun lastPlaybackUsedMobileQuality(accountKey: String): Boolean? =
        context.appSettingsDataStore.data.first()[lastPlaybackMobileQualityKey(accountKey)]

    suspend fun setLastPlaybackUsedMobileQuality(accountKey: String, value: Boolean) = edit {
        it[lastPlaybackMobileQualityKey(accountKey)] = value
    }
    suspend fun setCheckUpdates(value: Boolean) = edit { it[CHECK_UPDATES] = value }

    suspend fun lastUpdateCheckEpochMs(): Long =
        context.appSettingsDataStore.data.first()[LAST_UPDATE_CHECK_EPOCH_MS] ?: 0L

    suspend fun setLastUpdateCheckEpochMs(value: Long) = edit {
        it[LAST_UPDATE_CHECK_EPOCH_MS] = value.coerceAtLeast(0L)
    }

    suspend fun ignoredUpdateVersion(): String? =
        context.appSettingsDataStore.data.first()[IGNORED_UPDATE_VERSION]

    suspend fun setIgnoredUpdateVersion(value: String) = edit {
        it[IGNORED_UPDATE_VERSION] = value.trim()
    }

    suspend fun addRecentSearch(accountKey: String, query: String) = edit { preferences ->
        val key = recentSearchesKey(accountKey)
        val normalized = query.trim().replace(SEARCH_SEPARATOR, " ")
        if (normalized.isNotBlank()) {
            val current = preferences[key]
                ?.split(SEARCH_SEPARATOR)
                .orEmpty()
            preferences[key] = (listOf(normalized) + current)
                .distinctBy { it.lowercase() }
                .take(MAX_RECENT_SEARCHES)
                .joinToString(SEARCH_SEPARATOR)
        }
    }

    suspend fun removeRecentSearch(accountKey: String, query: String) = edit { preferences ->
        val key = recentSearchesKey(accountKey)
        preferences[key] = preferences[key]
            ?.split(SEARCH_SEPARATOR)
            .orEmpty()
            .filterNot { it.equals(query, ignoreCase = true) }
            .joinToString(SEARCH_SEPARATOR)
    }

    suspend fun clearRecentSearches(accountKey: String) = edit { it.remove(recentSearchesKey(accountKey)) }

    suspend fun setLastLibrarySyncEpochMs(accountKey: String, value: Long) = edit {
        it[lastLibrarySyncKey(accountKey)] = value.coerceAtLeast(0L)
    }

    suspend fun songMetadataVerificationEpochMs(accountKey: String, songId: String): Long =
        context.appSettingsDataStore.data.first()[songMetadataVerifiedKey(accountKey, songId)] ?: 0L

    suspend fun songMetadataAttemptEpochMs(accountKey: String, songId: String): Long =
        context.appSettingsDataStore.data.first()[songMetadataAttemptKey(accountKey, songId)] ?: 0L

    suspend fun markSongMetadataAttempt(accountKey: String, songId: String, value: Long) = edit {
        it[songMetadataAttemptKey(accountKey, songId)] = value.coerceAtLeast(0L)
    }

    suspend fun markSongMetadataVerified(accountKey: String, songId: String, value: Long) = edit {
        val checkedAt = value.coerceAtLeast(0L)
        it[songMetadataAttemptKey(accountKey, songId)] = checkedAt
        it[songMetadataVerifiedKey(accountKey, songId)] = checkedAt
    }

    suspend fun setFavoriteSongSort(value: String) = edit {
        it[FAVORITE_SONG_SORT] = value
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.appSettingsDataStore.edit(block)
    }

    private companion object {
        val SONG_BATCH_HINT_SHOWN = booleanPreferencesKey("song_batch_hint_shown")
        val THEME = stringPreferencesKey("theme")
        val VOLUME_NORMALIZATION = booleanPreferencesKey("volume_normalization")
        val EQUALIZER_ENABLED = booleanPreferencesKey("equalizer_enabled")
        val EQUALIZER_PRESET = stringPreferencesKey("equalizer_preset")
        val EQUALIZER_CUSTOM_LEVELS = stringPreferencesKey("equalizer_custom_levels")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only_downloads")
        val PLAYBACK_CACHE_SIZE_MB = intPreferencesKey("playback_cache_size_mb")
        val MOBILE_BIT_RATE = intPreferencesKey("mobile_bit_rate")
        val MOBILE_AUDIO_QUALITY = stringPreferencesKey("mobile_audio_quality")
        val CHECK_UPDATES = booleanPreferencesKey("check_updates")
        val LAST_UPDATE_CHECK_EPOCH_MS = longPreferencesKey("last_update_check_epoch_ms")
        val IGNORED_UPDATE_VERSION = stringPreferencesKey("ignored_update_version")
        fun recentSearchesKey(accountKey: String) = stringPreferencesKey("account_${accountKey}_recent_searches")
        fun lastLibrarySyncKey(accountKey: String) = longPreferencesKey("account_${accountKey}_last_library_sync")
        fun songMetadataAttemptKey(accountKey: String, songId: String) =
            longPreferencesKey("account_${accountKey}_song_${songStorageKey(songId)}_metadata_attempt")
        fun songMetadataVerifiedKey(accountKey: String, songId: String) =
            longPreferencesKey("account_${accountKey}_song_${songStorageKey(songId)}_metadata_verified")
        fun songStorageKey(songId: String): String = MessageDigest.getInstance("SHA-256")
            .digest(songId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        fun lastPlaybackMobileQualityKey(accountKey: String) =
            booleanPreferencesKey("account_${accountKey}_last_playback_mobile_quality")
        val FAVORITE_SONG_SORT = stringPreferencesKey("favorite_song_sort")
        const val SEARCH_SEPARATOR = "\u001F"
        const val EQUALIZER_SEPARATOR = ","
        const val MAX_RECENT_SEARCHES = 8
    }
}

data class AccountLibrarySettings(
    val recentSearches: List<String> = emptyList(),
    val lastLibrarySyncEpochMs: Long = 0L,
)
