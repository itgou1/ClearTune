package com.cleartune.app.auth

import androidx.lifecycle.ViewModel
import org.junit.Assert.*
import org.junit.Test

class AccountViewModelsTest {
    @Test fun logoutDiscardsOldPageStateAndModels() {
        val owner = AccountViewModels()
        val old = Probe()
        owner.store.put("library", old)
        owner.clearSession()
        assertTrue(old.cleared)
        assertNull(owner.store.get("library"))
        val fresh = Probe()
        owner.store.put("library", fresh)
        assertSame(fresh, owner.store.get("library"))
        assertFalse(fresh.cleared)
        owner.clearSession()
    }

    private class Probe : ViewModel() {
        var cleared = false
        override fun onCleared() { cleared = true }
    }
}
