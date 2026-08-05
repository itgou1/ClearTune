package com.cleartune.core.designsystem.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SafeArtworkTest {
    @Test
    fun `only app-safe non-network artwork references are loadable`() {
        assertEquals("content://com.cleartune.artwork/cache/a.jpg", safeArtworkModel("content://com.cleartune.artwork/cache/a.jpg"))
        assertEquals("android.resource://com.cleartune.app/123", safeArtworkModel("android.resource://com.cleartune.app/123"))
        assertNull(safeArtworkModel("file:///data/user/0/com.cleartune.app/files/secret.jpg"))
        assertNull(safeArtworkModel("https://person:secret@example.test/art.jpg"))
        assertNull(safeArtworkModel("javascript:alert(1)"))
    }
}
