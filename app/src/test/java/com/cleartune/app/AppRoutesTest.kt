package com.cleartune.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRoutesTest {
    @Test
    fun `library is the single root and there is no bottom navigation`() {
        assertEquals("library", AppRoutes.Library)
        assertFalse(AppRoutes.all.contains("bottom_navigation"))
        assertEquals(AppRoutes.all.size, AppRoutes.all.distinct().size)
    }

    @Test
    fun `playlist detail route round trips a saved identifier`() {
        val route = AppRoutes.playlistDetail("mix / favorites")

        assertEquals("mix / favorites", AppRoutes.playlistId(route))
        assertEquals(route, AppRoutes.restore(route))
    }

    @Test
    fun `unknown restored routes safely return to library`() {
        assertEquals(AppRoutes.Library, AppRoutes.restore("removed-destination"))
        assertTrue(AppRoutes.restorable.contains(AppRoutes.Player))
        assertTrue(AppRoutes.restorable.contains(AppRoutes.Settings))
    }
}
