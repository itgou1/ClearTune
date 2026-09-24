package com.cleartune.app.metadata

import com.cleartune.core.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owned by the account's scope, independent of the currently open editor. */
internal class MusicTagSyncQueue(
    private val scope: CoroutineScope,
    private val sync: suspend (Song) -> Boolean,
    private val active: () -> Boolean,
    private val finished: suspend (Song, Boolean) -> Unit,
) {
    private val mutableStates = MutableStateFlow<Map<String, MusicTagSyncStatus>>(emptyMap())
    val states = mutableStates.asStateFlow()
    private val mutex = Mutex()
    private var nextAttempt = 0L

    fun start(song: Song): Boolean {
        if (!active() || mutableStates.value[song.id]?.running == true) return false
        val status = MusicTagSyncStatus(song, true, ++nextAttempt)
        mutableStates.update { it + (song.id to status) }
        scope.launch {
            try {
                val synced = try {
                    mutex.withLock { if (active()) sync(song) else false }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
                if (active()) finished(song, synced)
                mutableStates.update { if (synced) it - song.id else it + (song.id to status.copy(running = false)) }
            } finally {
                mutableStates.update { states ->
                    if (states[song.id]?.attempt == status.attempt && states[song.id]?.running == true)
                        states + (song.id to status.copy(running = false)) else states
                }
            }
        }
        return true
    }
}
