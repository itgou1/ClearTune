package com.cleartune.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkFailureTest {
    @Test
    fun classifies_http_status_without_leaking_request_details() {
        val auth = NetworkFailure.fromHttpStatus(401)
        val locked = NetworkFailure.fromHttpStatus(423)
        val server = NetworkFailure.fromHttpStatus(503)

        assertEquals(NetworkFailureCode.AUTHENTICATION, auth.code)
        assertFalse(auth.retryable)
        assertEquals(NetworkFailureCode.LOCKED, locked.code)
        assertFalse(locked.retryable)
        assertEquals(NetworkFailureCode.SERVER, server.code)
        assertTrue(server.retryable)
        assertFalse(server.toString().contains("Authorization", ignoreCase = true))
    }
}
