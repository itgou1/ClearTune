package com.cleartune.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MediaControllerLifecycleTest {
    @Test
    fun `disconnect releases old controller and reconnect preserves queued command order`() = runTest {
        val firstConnection = CompletableDeferred<FakeController>()
        val secondConnection = CompletableDeferred<FakeController>()
        val connections = ArrayDeque(listOf(firstConnection, secondConnection))
        val released = mutableListOf<String>()
        val connectedStates = mutableListOf<Boolean>()
        val lifecycle = ControllerLifecycle(
            connect = { connections.removeFirst().await() },
            releaseController = { released += it.id },
            onConnectionChanged = { connectedStates += it },
        )
        val commands = mutableListOf<String>()

        val initial = async { lifecycle.execute { commands += "first" } }
        runCurrent()
        assertFalse(lifecycle.connected)
        val first = FakeController("old")
        firstConnection.complete(first)
        initial.await()
        assertTrue(lifecycle.connected)

        lifecycle.disconnect(first)
        assertFalse(lifecycle.connected)
        assertEquals(listOf("old"), released)

        val second = async { lifecycle.execute { commands += "second" } }
        val third = async { lifecycle.execute { commands += "third" } }
        runCurrent()
        assertEquals(listOf("first"), commands)
        secondConnection.complete(FakeController("new"))
        awaitAll(second, third)

        assertEquals(listOf("first", "second", "third"), commands)
        assertTrue(lifecycle.connected)
        assertEquals(listOf(true, false, true), connectedStates)
    }

    @Test
    fun `exceptional connection is truthful and the next queued command retries`() = runTest {
        val failed = CompletableDeferred<FakeController>()
        val recovered = CompletableDeferred<FakeController>()
        val connections = ArrayDeque(listOf(failed, recovered))
        val lifecycle = ControllerLifecycle(
            connect = { connections.removeFirst().await() },
            releaseController = {},
        )
        val commands = mutableListOf<String>()

        val first = async {
            runCatching { lifecycle.execute { commands += "failed-command" } }
        }
        val second = async { lifecycle.execute { commands += "recovered-command" } }
        runCurrent()
        failed.completeExceptionally(IllegalStateException("session unavailable"))
        advanceUntilIdle()
        assertFalse(lifecycle.connected)
        recovered.complete(FakeController("recovered"))
        awaitAll(first, second)

        assertEquals(listOf("recovered-command"), commands)
        assertTrue(lifecycle.connected)
    }
}

private data class FakeController(val id: String)
