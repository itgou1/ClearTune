package com.cleartune.core.network

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PlaylistEntryTimeTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private fun parse(extra: String = "", entry: String = ""): PlaylistEntryTime {
        val dto = json.decodeFromString<PlaylistDetailDto>(
            """{"id":"p","name":"P"$extra,"entry":[{"id":"s","title":"S"$entry}]}""")
        return playlistEntryTime(dto.supportsAddedAt, dto.entry.single().addedAt)
    }
    @Test fun originalServerDoesNotUseLocalHistory() {
        assertEquals(PlaylistEntryTime(allowLocalTime = false), parse())
    }
    @Test fun capabilityControlsMissingTimes() {
        assertEquals(PlaylistEntryTime(allowLocalTime = false), parse(",\"supportsAddedAt\":true"))
        assertEquals(PlaylistEntryTime(allowLocalTime = false), parse(",\"supportsAddedAt\":false"))
    }
    @Test fun timestampsSupportOffsetsFractionsAndLegacyCustomServer() {
        val time = ",\"addedAt\":\"2026-10-13T11:55:00.123+08:00\""
        val expected = java.time.Instant.parse("2026-10-13T03:55:00.123Z").toEpochMilli()
        assertEquals(PlaylistEntryTime(expected, false), parse(entry = time))
        assertEquals(PlaylistEntryTime(expected, false), parse(",\"supportsAddedAt\":true", time))
        assertEquals(PlaylistEntryTime(allowLocalTime = false), parse(",\"supportsAddedAt\":false", time))
    }
    @Test fun nullAndMalformedTimesDoNotBreakSongParsingOrInventDates() {
        listOf("null", "123", "true", "{}", "[]", "\"bad-date\"").forEach { value ->
            assertEquals(PlaylistEntryTime(allowLocalTime = false), parse(entry = ",\"addedAt\":$value"))
            assertEquals(PlaylistEntryTime(allowLocalTime = false), parse(",\"supportsAddedAt\":true", ",\"addedAt\":$value"))
        }
    }
}
