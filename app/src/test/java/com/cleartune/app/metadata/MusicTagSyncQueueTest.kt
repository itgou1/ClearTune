package com.cleartune.app.metadata

import com.cleartune.core.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class MusicTagSyncQueueTest {
    private val first = Song("first", "First")
    private val second = Song("second", "Second")

    @Test fun deduplicatesAndSerializesWhileCallerCanContinue() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val finished = mutableListOf<String>()
        val queue = MusicTagSyncQueue(scope, { song ->
            calls += song.id
            if (song.id == first.id) gate.await()
            true
        }, { true }, { song, _ -> finished += song.id })
        try {
            assertTrue(queue.start(first))
            assertFalse(queue.start(first))
            assertTrue(queue.start(second))
            assertEquals(listOf("first"), calls)
            assertTrue(finished.isEmpty())
            assertEquals(2, queue.states.value.size)
            gate.complete(Unit)
            withTimeout(1000) { while (queue.states.value.isNotEmpty()) yield() }
            assertEquals(listOf("first", "second"), calls)
            assertEquals(calls, finished)
        } finally { scope.cancel() }
    }

    @Test fun failureIsRetryableAndCancellationDoesNotReportSuccess() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var calls = 0
        val results = mutableListOf<Boolean>()
        val queue = MusicTagSyncQueue(scope, {
            calls++
            if (calls == 1) error("offline")
            awaitCancellation()
        }, { true }, { _, success -> results += success })
        assertTrue(queue.start(first))
        assertFalse(queue.states.value.getValue(first.id).running)
        val firstAttempt = queue.states.value.getValue(first.id).attempt
        assertEquals(listOf(false), results)
        assertTrue(queue.start(first))
        assertTrue(queue.states.value.getValue(first.id).running)
        assertTrue(queue.states.value.getValue(first.id).attempt > firstAttempt)
        scope.cancel()
        assertFalse(queue.states.value.getValue(first.id).running)
        assertEquals(2, calls)
        assertEquals(listOf(false), results)
    }

    @Test fun dismissingSyncNoticeDoesNotDiscardWorkAndAnewAttemptCanBeShown() {
        val running = MusicTagSyncStatus(first, running = true, attempt = 1)
        val dismissed = mapOf(first.id to (running.attempt to true))
        assertEquals(running, visibleSyncNotice(listOf(running), emptyMap()))
        assertEquals(null, visibleSyncNotice(listOf(running), dismissed))
        assertEquals(running.copy(running = false), visibleSyncNotice(listOf(running.copy(running = false)), dismissed))
        val retry = running.copy(attempt = 2)
        assertEquals(retry, visibleSyncNotice(listOf(retry), dismissed))
    }

    @Test fun revokedAccountDoesNotPublishCompletionOrStartQueuedSong() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>()
        var active = true
        var calls = 0
        var completed = 0
        val queue = MusicTagSyncQueue(scope, { calls++; gate.await(); true }, { active }, { _, _ -> completed++ })
        try {
            queue.start(first)
            queue.start(second)
            active = false
            gate.complete(Unit)
            assertFalse(queue.start(first))
            assertEquals(1, calls)
            assertEquals(0, completed)
        } finally { scope.cancel() }
    }

    @Test fun completionNeverReopensEditorOrOverwritesAnotherDraft() {
        val closed = MusicTagUiState()
        assertEquals(closed, musicTagSyncFinished(closed, first, true))
        val editing = MusicTagUiState(song = second, values = mapOf(MusicTagField.TITLE to "draft"))
        assertEquals(editing, musicTagSyncFinished(editing, first, true))
        val freshDraft = MusicTagUiState(song = first, snapshot = MusicTagSnapshot("/a.mp3", emptyMap()),
            values = mapOf(MusicTagField.TITLE to "unsaved"))
        assertEquals(freshDraft, musicTagSyncFinished(freshDraft, first, true))
        val syncing = MusicTagUiState(song = first, pending = true, syncing = true, fileSaved = true)
        assertFalse(musicTagSyncFinished(syncing, first, true).pending)
        val failed = musicTagSyncFinished(syncing, first, false)
        assertTrue(failed.pending)
        assertTrue(failed.fileSaved)
        assertFalse(failed.syncing)
    }
}
