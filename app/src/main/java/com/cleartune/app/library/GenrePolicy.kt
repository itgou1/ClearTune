package com.cleartune.app.library

import java.util.Locale

private val metadataIdentifierPattern = Regex("^\\d+(?:[_:/-]\\d+)+$")
private val singleLetterMetadataPattern = Regex("^[A-Za-z]$")
private val repeatedSeparatorPattern = Regex("[_:/-]{2,}")
private val whitespacePattern = Regex("\\s+")

private val genreAliases = mapOf(
    "pop" to "流行",
    "流行" to "流行",
    "流行音乐" to "流行",
    "chinese pop" to "华语流行",
    "chinese pop music" to "华语流行",
    "华语流行" to "华语流行",
    "华语流行音乐" to "华语流行",
    "华语流行音乐 chinese pop music" to "华语流行",
    "rock" to "摇滚",
    "摇滚" to "摇滚",
    "摇滚音乐" to "摇滚",
    "blues" to "蓝调",
    "蓝调" to "蓝调",
    "布鲁斯" to "蓝调",
    "jazz" to "爵士",
    "爵士" to "爵士",
    "classical" to "古典",
    "古典" to "古典",
    "folk" to "民谣",
    "民谣" to "民谣",
    "country folk" to "乡村与民谣",
    "country and folk" to "乡村与民谣",
    "乡村与民谣" to "乡村与民谣",
    "other" to "其他",
    "others" to "其他",
    "其他" to "其他",
)

/**
 * Returns a user-facing genre label without mutating the server metadata.
 * Identifier-shaped values are deliberately hidden because some servers expose internal tag IDs
 * through the OpenSubsonic genre field.
 */
internal fun normalizeGenreLabel(value: String?): String? {
    val trimmed = value
        ?.trim()
        ?.replace('【', ' ')
        ?.replace('】', ' ')
        ?.replace('(', ' ')
        ?.replace(')', ' ')
        ?.replace('&', ' ')
        ?.replace(whitespacePattern, " ")
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: return null
    if (
        metadataIdentifierPattern.matches(trimmed) ||
        singleLetterMetadataPattern.matches(trimmed) ||
        repeatedSeparatorPattern.containsMatchIn(trimmed)
    ) {
        return null
    }
    val aliasKey = trimmed
        .lowercase(Locale.ROOT)
        .replace(whitespacePattern, " ")
        .trim()
    return genreAliases[aliasKey] ?: value.trim()
}

internal fun normalizeGenreLabels(values: Iterable<String?>): List<String> = values
    .mapNotNull(::normalizeGenreLabel)
    .distinctBy(::genreComparisonKey)

internal fun genreLabelsMatch(first: String?, second: String?): Boolean {
    val firstLabel = normalizeGenreLabel(first) ?: return false
    val secondLabel = normalizeGenreLabel(second) ?: return false
    return genreComparisonKey(firstLabel) == genreComparisonKey(secondLabel)
}

private fun genreComparisonKey(value: String): String = value
    .lowercase(Locale.ROOT)
    .replace(whitespacePattern, " ")
    .trim()
