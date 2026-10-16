package com.cleartune.core.network

import com.cleartune.core.model.MusicTagSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MusicTagEditorConnectionTest {
    private val settings = MusicTagSettings("https://example.invalid", "user", "secret")
    private class Fixture(scope: CoroutineScope) {
        var opens = 0
        var closes = 0
        val manager = MusicTagEditorConnection(scope, MusicTagEditorConnection.IDLE_TIMEOUT_MILLIS) { settings, _, use ->
            opens++
            val client = MusicTagClient(settings)
            try { use(client) } finally { client.close(); closes++ }
        }
        init { manager.enter() }
    }

    @Test fun reusesClientAndResetsSixtySecondIdleDeadlineAfterCompletion() = runTest {
        val f = Fixture(backgroundScope)
        val first = f.manager.withClient(settings) { it }
        advanceTimeBy(59_999); runCurrent()
        assertEquals(0, f.closes)
        assertSame(first, f.manager.withClient(settings) { it })
        assertEquals(1, f.opens)
        advanceTimeBy(59_999); runCurrent()
        assertEquals(0, f.closes)
        advanceTimeBy(1); runCurrent()
        assertEquals(1, f.closes)
        assertNotSame(first, f.manager.withClient(settings) { it })
        f.manager.leave(); runCurrent()
        assertEquals(2, f.closes)
    }

    @Test fun idleTimerCannotCutOffSaveAndReadbackEvenAfterTwoMinutes() = runTest {
        val f = Fixture(backgroundScope)
        f.manager.withClient(settings) {}
        val saving = launch {
            f.manager.withClient(settings) {
                delay(90_000) // Slow write.
                assertEquals(0, f.closes)
                delay(30_000) // Readback verification is part of the same operation.
                assertEquals(0, f.closes)
            }
        }
        runCurrent(); advanceTimeBy(120_000); runCurrent()
        saving.join()
        assertEquals(1, f.opens)
        assertEquals(0, f.closes)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, f.closes)
    }

    @Test fun leavingDuringSaveDefersCloseUntilVerificationFinishes() = runTest {
        val f = Fixture(backgroundScope)
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val saving = launch { f.manager.withClient(settings) { entered.complete(Unit); finish.await() } }
        entered.await()
        f.manager.leave(); runCurrent()
        advanceTimeBy(120_000); runCurrent()
        assertEquals(0, f.closes)
        finish.complete(Unit); saving.join()
        assertEquals(1, f.closes)
        assertTrue(runCatching { f.manager.withClient(settings) {} }.isFailure)
    }

    @Test fun leaveAndReturnDoesNotKeepTheOldInFlightConnection() = runTest {
        val f = Fixture(backgroundScope)
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val active = launch { f.manager.withClient(settings) { entered.complete(Unit); finish.await() } }
        entered.await(); f.manager.leave(); f.manager.enter()
        finish.complete(Unit); active.join()
        assertEquals(1, f.closes)
        f.manager.withClient(settings) {}
        assertEquals(2, f.opens)
        f.manager.leave(); runCurrent()
    }

    @Test fun queuedOperationCannotReconnectAfterLeavingPage() = runTest {
        val f = Fixture(backgroundScope)
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val active = launch { f.manager.withClient(settings) { entered.complete(Unit); finish.await() } }
        entered.await()
        val queued = async { runCatching { f.manager.withClient(settings) { fail("stale request ran") } } }
        runCurrent(); f.manager.leave(); f.manager.enter(); finish.complete(Unit)
        active.join()
        assertTrue(queued.await().isFailure)
        assertEquals(1, f.opens)
    }

    @Test fun failuresDiscardConnectionWithoutReplayingOperation() = runTest {
        val f = Fixture(backgroundScope)
        var writes = 0
        assertTrue(runCatching { f.manager.withClient(settings) { writes++; error("uncertain write") } }.isFailure)
        assertEquals(1, writes)
        assertEquals(1, f.closes)
        f.manager.withClient(settings) {}
        assertEquals(2, f.opens)
        f.manager.leave(); runCurrent()
    }

    @Test fun switchingCredentialsReopensButChangingSearchSourceDoesNot() = runTest {
        val f = Fixture(backgroundScope)
        f.manager.withClient(settings) {}
        f.manager.withClient(settings.copy(source = "kuwo")) {}
        assertEquals(1, f.opens)
        f.manager.withClient(settings.copy(password = "new-secret")) {}
        assertEquals(2, f.opens)
        assertEquals(1, f.closes)
        f.manager.leave(); runCurrent()
    }
}
