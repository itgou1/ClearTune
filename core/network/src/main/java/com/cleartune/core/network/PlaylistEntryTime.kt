package com.cleartune.core.network

import java.time.OffsetDateTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// Missing and invalid membership times both fall back to the song's library time.
internal val MissingPlaylistTime = kotlinx.serialization.json.JsonObject(emptyMap())

data class PlaylistEntryTime(val addedAt: Long? = null, val allowLocalTime: Boolean = false)

internal fun playlistEntryTime(supported: Boolean?, raw: JsonElement): PlaylistEntryTime {
    if (supported == false) return PlaylistEntryTime(allowLocalTime = false)
    val time = (raw as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.let {
        runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
    }
    return PlaylistEntryTime(time)
}
