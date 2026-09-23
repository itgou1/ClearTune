package com.cleartune.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTitleTest {
    @Test fun shortTitleNeverScrolls() {
        assertEquals(0f, titleScrollPosition(9_000.0, 0f, 24f), 0f)
    }

    @Test fun newTitleWaitsTwoSeconds() {
        assertEquals(0f, titleScrollPosition(1_999.0, 240f, 24f), 0f)
        assertEquals(0f, titleScrollPosition(2_000.0, 240f, 24f), 0f)
        assertEquals(24f, titleScrollPosition(3_000.0, 240f, 24f), 0.001f)
    }

    @Test fun endOfTitlePausesBeforeReturningToStart() {
        assertEquals(240f, titleScrollPosition(12_000.0, 240f, 24f), 0.001f)
        assertEquals(240f, titleScrollPosition(13_999.0, 240f, 24f), 0.001f)
        assertEquals(0f, titleScrollPosition(14_000.0, 240f, 24f), 0.001f)
        assertEquals(0f, titleScrollPosition(15_999.0, 240f, 24f), 0.001f)
    }
}
