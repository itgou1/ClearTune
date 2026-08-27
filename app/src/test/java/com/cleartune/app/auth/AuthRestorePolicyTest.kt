package com.cleartune.app.auth

import com.cleartune.core.model.ClearTuneError
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRestorePolicyTest {
    @Test
    fun networkFailuresAllowCachedOfflineSession() {
        assertTrue(ClearTuneError.Timeout().allowsOfflineRestore())
        assertTrue(ClearTuneError.Unreachable().allowsOfflineRestore())
    }

    @Test
    fun identityAndServerFailuresNeverBypassLogin() {
        assertFalse(ClearTuneError.Authentication().allowsOfflineRestore())
        assertFalse(ClearTuneError.InvalidAddress().allowsOfflineRestore())
        assertFalse(ClearTuneError.InsecureHttpBlocked().allowsOfflineRestore())
        assertFalse(ClearTuneError.Server().allowsOfflineRestore())
        assertFalse(ClearTuneError.Unexpected().allowsOfflineRestore())
    }
}
