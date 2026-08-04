package com.cleartune.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppRoutesTest {
    @Test
    fun `library is the single root and there is no bottom navigation`() {
        assertEquals("library", AppRoutes.Library)
        assertFalse(AppRoutes.all.contains("bottom_navigation"))
        assertEquals(AppRoutes.all.size, AppRoutes.all.distinct().size)
    }
}
