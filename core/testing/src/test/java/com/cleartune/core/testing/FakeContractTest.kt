package com.cleartune.core.testing

import com.cleartune.core.model.LibraryHome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class FakeContractTest {
    @Test
    fun fake_library_emits_seeded_home() = runTest {
        val fake = FakeLibraryRepository(home = LibraryHome(songCount = 3))
        assertEquals(3, fake.observeLibraryHome().first().songCount)
    }
}
