package com.cleartune.app.library

import com.cleartune.core.model.ClearTuneError
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncConsistencyPolicyTest {
    @Test
    fun recognizesSubsonicAndHttpNotFoundResponses() {
        assertTrue(ClearTuneError.Server(code = 70).isNotFound())
        assertTrue(ClearTuneError.Server(code = 404).isNotFound())
    }

    @Test
    fun doesNotDeleteCacheForTransientOrAuthenticationFailures() {
        assertFalse(ClearTuneError.Server(code = 500).isNotFound())
        assertFalse(ClearTuneError.Timeout().isNotFound())
        assertFalse(ClearTuneError.Authentication().isNotFound())
        assertFalse(null.isNotFound())
    }
}
