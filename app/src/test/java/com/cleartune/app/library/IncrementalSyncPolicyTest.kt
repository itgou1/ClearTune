package com.cleartune.app.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncrementalSyncPolicyTest {
    private val now = 10L * 24 * 60 * 60 * 1_000

    @Test fun playedSongIsCheckedAtMostOncePerDay() {
        assertFalse(shouldVerifyPlayedSong(now - 60_000, 0, now))
        assertTrue(shouldVerifyPlayedSong(now - PLAYED_SONG_VERIFICATION_MAX_AGE_MS, 0, now))
    }

    @Test fun failedPlayedSongCheckBacksOffBeforeRetrying() {
        assertFalse(shouldVerifyPlayedSong(0, now - 60_000, now))
        assertTrue(shouldVerifyPlayedSong(0, now - PLAYED_SONG_RETRY_DELAY_MS, now))
    }

    @Test fun albumAndArtistDetailsRefreshAfterOneDay() {
        assertFalse(shouldRefreshDetail(now - 60_000, now))
        assertTrue(shouldRefreshDetail(now - DETAIL_REFRESH_MAX_AGE_MS, now))
        assertTrue(shouldRefreshDetail(null, now))
    }

    @Test fun automaticFullReconciliationIsLowFrequency() {
        assertFalse(shouldReconcileLibrary(0, now))
        assertFalse(shouldReconcileLibrary(now - 24 * 60 * 60 * 1_000, now))
        assertTrue(shouldReconcileLibrary(now - AUTOMATIC_LIBRARY_RECONCILIATION_AGE_MS, now))
    }
}
