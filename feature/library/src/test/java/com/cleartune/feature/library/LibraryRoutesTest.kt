package com.cleartune.feature.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryRoutesTest {
    @Test
    fun employee_owned_routes_are_namespaced_under_library() {
        assertEquals("library/songs", LibraryRoutes.songs)
        assertEquals("library/albums", LibraryRoutes.albums)
        assertEquals("library/artists", LibraryRoutes.artists)
        assertEquals("library/folders", LibraryRoutes.folders)
        assertEquals("library/search", LibraryRoutes.search)
    }
}
