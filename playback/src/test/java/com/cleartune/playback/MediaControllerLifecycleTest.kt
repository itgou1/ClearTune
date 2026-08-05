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
        val dispatcher = RecordingControllerDispatcher(released)
        val connectedStates = mutableListOf<Boolean>()
        val lifecycle = ControllerLifecycle(
            connect = { connections.removeFirst().await() },
            dispatcher = dispatcher,
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
            dispatcher = RecordingControllerDispatcher(mutableListOf()),
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

    @Test
    fun `every command and release is routed through controller dispatcher`() = runTest {
        val released = mutableListOf<String>()
        val dispatcher = RecordingControllerDispatcher(released)
        val lifecycle = ControllerLifecycle(
            connect = { FakeController("routed") },
            dispatcher = dispatcher,
        )

        lifecycle.execute { assertTrue(dispatcher.inDispatch); it.id }
        lifecycle.disconnect(lifecycle.currentOrNull()!!)

        assertEquals(listOf("execute:routed", "release:routed"), dispatcher.routes)
        assertEquals(listOf("routed"), released)
    }

    @Test
    fun `late connection after release never becomes current`() = runTest {
        val pending = CompletableDeferred<FakeController>()
        val released = mutableListOf<String>()
        val lifecycle = ControllerLifecycle(
            connect = { pending.await() },
            dispatcher = RecordingControllerDispatcher(released),
        )

        val command = async { runCatching { lifecycle.execute { it.id } } }
        runCurrent()
        lifecycle.release()
        pending.complete(FakeController("late"))
        advanceUntilIdle()

        assertTrue(command.await().isFailure)
        assertFalse(lifecycle.connected)
        assertEquals(listOf("late"), released)
    }

    @Test
    fun `disconnect before assignment rejects and releases connection`() = runTest {
        val released = mutableListOf<String>()
        lateinit var lifecycle: ControllerLifecycle<FakeController>
        val controller = FakeController("disconnected-early")
        lifecycle = ControllerLifecycle(
            connect = {
                lifecycle.disconnect(controller)
                controller
            },
            dispatcher = RecordingControllerDispatcher(released),
        )

        val result = runCatching { lifecycle.execute { it.id } }

        assertTrue(result.isFailure)
        assertFalse(lifecycle.connected)
        assertEquals(listOf("disconnected-early"), released)
    }

    @Test
    fun `command exception releases cached controller and next FIFO command reconnects`() = runTest {
        val connections = ArrayDeque(listOf(FakeController("bad"), FakeController("good")))
        val released = mutableListOf<String>()
        val lifecycle = ControllerLifecycle(
            connect = { connections.removeFirst() },
            dispatcher = RecordingControllerDispatcher(released),
        )

        val first = async { runCatching { lifecycle.execute<Unit> { error("binder died") } } }
        val second = async { lifecycle.execute { it.id } }
        val results = awaitAll(first, second)

        assertTrue((results[0] as Result<*>).isFailure)
        assertEquals("good", results[1])
        assertEquals(listOf("bad"), released)
        assertEquals("good", lifecycle.currentOrNull()?.id)
    }
}

private data class FakeController(val id: String)

private class RecordingControllerDispatcher(
    private val released: MutableList<String>,
) : ControllerDispatcher<FakeController> {
    val routes = mutableListOf<String>()
    var inDispatch = false

    override suspend fun <R> execute(controller: FakeController, command: (FakeController) -> R): R {
        routes += "execute:${controller.id}"
        inDispatch = true
        return try {
            command(controller)
        } finally {
            inDispatch = false
        }
    }

    override fun release(controller: FakeController) {
        routes += "release:${controller.id}"
        released += controller.id
    }
}
