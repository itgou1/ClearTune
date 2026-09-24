package com.cleartune.app.library

import com.cleartune.core.model.RecommendationShelf
import java.time.LocalDate

internal data class RecommendationSurfaces(
    val random: RecommendationShelf?,
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
        DISCOVERY_SHELF_IDS.mapNotNull(byId::get)
    }
    return RecommendationSurfaces(
        random = byId["random"],
        frequent = byId["frequent"],
        discovery = discovery,
    )
}

internal fun dailyRecommendationSeed(date: LocalDate = LocalDate.now()): Long = date.toEpochDay()

private val DISCOVERY_SHELF_IDS = listOf("from-favorites", "new-taste", "long-absent")
private const val MIN_LIBRARY_SIZE_FOR_DISCOVERY = 20
