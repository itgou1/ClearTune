package com.cleartune.app.player

import com.cleartune.core.model.InternetRadioStation

data class RadioUiState(
    val stations: List<InternetRadioStation> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

// Keep the visible order stable during refresh; new stations appear at the end.
internal fun mergeRadioStations(
    current: List<InternetRadioStation>,
    incoming: List<InternetRadioStation>,
): List<InternetRadioStation> {
    val remaining = incoming.associateByTo(linkedMapOf()) { it.id }
    val merged = buildList {
        current.forEach { old ->
            remaining.remove(old.id)?.let { updated -> add(if (old == updated) old else updated) }
        }
        addAll(remaining.values)
    }
    return if (merged == current) current else merged
}
