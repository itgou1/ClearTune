package com.cleartune.core.network

import java.time.OffsetDateTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// Identity distinguishes an absent field from explicit JSON null or malformed objects.
internal val MissingPlaylistTime = kotlinx.serialization.json.JsonObject(emptyMap())

data class PlaylistEntryTime(val addedAt: Long? = null, val allowLocalTime: Boolean = true)

internal fun playlistEntryTime(supported: Boolean?, raw: JsonElement): PlaylistEntryTime {
    if (supported == false) return PlaylistEntryTime(allowLocalTime = false)
    val time = (raw as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.let {
        runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
    }
    // Explicit null/invalid values are unknown; only absent legacy fields use local history.
    return PlaylistEntryTime(time, supported == null && raw === MissingPlaylistTime)
}
