package com.cleartune.app.library

import com.cleartune.core.model.RecommendationShelf
import java.time.LocalDate

internal data class RecommendationSurfaces(
    val rediscovery: RecommendationShelf?,
    val frequent: RecommendationShelf?,
    val discovery: List<RecommendationShelf>,
)

internal fun recommendationSurfaces(
    shelves: List<RecommendationShelf>,
    librarySongCount: Int,
): RecommendationSurfaces {
    val byId = shelves.associateBy(RecommendationShelf::id)
    val discovery = if (librarySongCount < MIN_LIBRARY_SIZE_FOR_DISCOVERY) {
        emptyList()
    } else {
        shelves.filter { it.id in DISCOVERY_SHELF_IDS }
    }
    return RecommendationSurfaces(
        rediscovery = byId["long-absent"] ?: byId["random"],
        frequent = byId["frequent"],
        discovery = discovery,
    )
}

internal fun dailyRecommendationSeed(date: LocalDate = LocalDate.now()): Long = date.toEpochDay()

private val DISCOVERY_SHELF_IDS = setOf("from-favorites", "new-taste", "random")
private const val MIN_LIBRARY_SIZE_FOR_DISCOVERY = 20
