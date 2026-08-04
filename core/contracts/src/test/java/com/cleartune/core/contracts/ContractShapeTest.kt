package com.cleartune.core.contracts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractShapeTest {
    @Test
    fun library_write_gateway_has_one_transaction_entrypoint() {
        assertEquals(
            1,
            LibraryWriteGateway::class.java.methods.count { it.name == "applyLibraryMutation" },
        )
    }

    @Test
    fun playback_library_repository_exposes_track_resolution() {
        assertTrue(
            PlaybackLibraryRepository::class.java.methods.any { it.name == "getPlayableTrack" },
        )
    }

    @Test
    fun frozen_contracts_do_not_expose_android_types() {
        val contractTypes = listOf(
            LibraryRepository::class.java,
            LibraryWriteGateway::class.java,
            PlaybackLibraryRepository::class.java,
            PlaybackGateway::class.java,
        )
        val exposedTypes = contractTypes
            .flatMap { it.methods.asList() }
            .flatMap { method -> method.parameterTypes.asList() + method.returnType }
        assertFalse(exposedTypes.any { it.name.startsWith("android.") || it.name.startsWith("androidx.") })
    }
}
