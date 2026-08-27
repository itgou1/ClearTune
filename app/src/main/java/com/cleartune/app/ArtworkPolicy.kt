package com.cleartune.app

/**
 * Older Navidrome versions may expose an album artwork id even when the album has no image, then
 * return Navidrome's blue-record placeholder from getCoverArt. A zero revision on an album id is
 * how that legacy response appears in the library data. Treat it as missing so ClearTune can use
 * its own deterministic fallback artwork instead.
 */
private val navidromeMissingAlbumArtwork = Regex(
    pattern = "^al-[^_]+_0+(?:[?#].*)?$",
    option = RegexOption.IGNORE_CASE,
)

internal fun String?.displayableArtworkId(): String? = this
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?.takeUnless(navidromeMissingAlbumArtwork::matches)
