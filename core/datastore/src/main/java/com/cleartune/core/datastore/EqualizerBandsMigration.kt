package com.cleartune.core.datastore

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlin.math.ln
import kotlin.math.roundToInt

private val legacyFrequencies = listOf(60, 230, 910, 3_600, 14_000)
private val levelsKey = stringPreferencesKey("equalizer_custom_levels")

/** Interpolate the old curve in log frequency; never reinterpret five values as ten bands. */
internal fun List<Int>.sanitizedEqualizerLevels(): List<Int> = when (size) {
    EQUALIZER_FREQUENCIES_HZ.size -> map { it.coerceIn(-6, 6) }
    legacyFrequencies.size -> {
        val levels = map { it.coerceIn(-6, 6) }
        EQUALIZER_FREQUENCIES_HZ.map { frequency ->
            when {
                frequency <= legacyFrequencies.first() -> levels.first()
                frequency >= legacyFrequencies.last() -> levels.last()
                else -> {
                    val right = legacyFrequencies.indexOfFirst { it >= frequency }
                    val left = right - 1
                    val fraction = ln(frequency.toDouble() / legacyFrequencies[left]) /
                        ln(legacyFrequencies[right].toDouble() / legacyFrequencies[left])
                    (levels[left] + (levels[right] - levels[left]) * fraction).roundToInt()
                }
            }
        }
    }
    else -> DEFAULT_EQUALIZER_LEVELS_DB
}

internal fun decodeEqualizerLevels(raw: String?): List<Int> {
    if (raw == null) return DEFAULT_EQUALIZER_LEVELS_DB
    // A malformed element must not shift subsequent bands or become a legacy curve.
    val values = raw.split(",").map { it.toIntOrNull() ?: return DEFAULT_EQUALIZER_LEVELS_DB }
    return values.sanitizedEqualizerLevels()
}

/** Persist the normalized curve before settings can reach the first AudioTrack. */
internal object EqualizerBandsMigration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val raw = currentData[levelsKey] ?: return false
        return raw != decodeEqualizerLevels(raw).joinToString(",")
    }

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences().apply {
            this[levelsKey] = decodeEqualizerLevels(currentData[levelsKey]).joinToString(",")
        }

    override suspend fun cleanUp() = Unit
}
