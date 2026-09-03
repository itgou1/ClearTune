package com.cleartune.app.library

import com.cleartune.core.model.RecommendationShelf
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecommendationPresentationPolicyTest {
    @Test
    fun todayAndDiscoveryUseDifferentRecommendationShelves() {
        val surfaces = recommendationSurfaces(
            shelves = listOf(
                shelf("long-absent"),
                shelf("recently-added"),
                shelf("from-favorites"),
                shelf("new-taste"),
                shelf("frequent"),
                shelf("random"),
            ),
            librarySongCount = 60,
        )

        assertEquals("long-absent", surfaces.rediscovery?.id)
        assertEquals("frequent", surfaces.frequent?.id)
        assertEquals(listOf("from-favorites", "new-taste", "random"), surfaces.discovery.map { it.id })
    }

    @Test
    fun smallLibraryKeepsRandomOnTodayAndHidesDiscovery() {
        val surfaces = recommendationSurfaces(
            shelves = listOf(shelf("random")),
            librarySongCount = 8,
        )

        assertEquals("random", surfaces.rediscovery?.id)
        assertNull(surfaces.frequent)
        assertEquals(emptyList<RecommendationShelf>(), surfaces.discovery)
    }

    @Test
    fun recommendationSeedIsStableForTheSameDay() {
        assertEquals(
            dailyRecommendationSeed(LocalDate.of(2026, 9, 2)),
            dailyRecommendationSeed(LocalDate.of(2026, 9, 2)),
        )
    }

    private fun shelf(id: String) = RecommendationShelf(id, id, id, emptyList())
}
