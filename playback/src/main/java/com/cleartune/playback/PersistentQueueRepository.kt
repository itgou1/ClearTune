package com.cleartune.playback

import com.cleartune.core.contracts.QueueRepository
import com.cleartune.core.model.QueueCommand
import com.cleartune.core.model.QueueItemId
import com.cleartune.core.model.QueueSnapshot
import com.cleartune.core.model.RepeatMode
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface QueueStorage {
    fun load(): QueueSnapshot?
    fun save(snapshot: QueueSnapshot)
    fun loadRecovery(): QueueRecoveryState? = load()?.let(::QueueRecoveryState)
    fun saveRecovery(state: QueueRecoveryState) = save(state.snapshot)
}

data class QueueRecoveryState(
    val snapshot: QueueSnapshot,
    val shuffleOrder: List<QueueItemId> = emptyList(),
)

interface PlaybackQueueStateWriter {
    suspend fun updatePlaybackState(
        currentIndex: Int? = null,
        positionMs: Long? = null,
        playWhenReady: Boolean? = null,
        repeatMode: RepeatMode? = null,
        shuffleEnabled: Boolean? = null,
    )
}

interface PlaybackQueueRecoveryProvider {
    suspend fun recoveryState(): QueueRecoveryState
}

class PersistentQueueRepository(
    private val storage: QueueStorage,
    private val createShuffleOrder: (List<QueueItemId>) -> List<QueueItemId> = { occurrences ->
        occurrences.sortedBy { occurrence ->
            MessageDigest.getInstance("SHA-256")
                .digest(occurrence.value.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
        }
    },
    private val createId: () -> QueueItemId = { QueueItemId(UUID.randomUUID().toString()) },
) : QueueRepository, PlaybackQueueStateWriter, PlaybackQueueRecoveryProvider {
    private val mutex = Mutex()
    private var recovery = normalize(storage.loadRecovery() ?: QueueRecoveryState(QueueSnapshot()))
    private val queue = MutableStateFlow(recovery.snapshot)

    override fun observeQueue(): Flow<QueueSnapshot> = queue

    override suspend fun apply(command: QueueCommand) = mutex.withLock {
        setAndSave(QueueReducer.reduce(queue.value, command, createId))
    }

    override suspend fun updatePlaybackState(
        currentIndex: Int?,
        positionMs: Long?,
        playWhenReady: Boolean?,
        repeatMode: RepeatMode?,
        shuffleEnabled: Boolean?,
    ) = mutex.withLock {
        setAndSave(
            queue.value.copy(
                currentIndex = currentIndex?.let { index ->
                    if (queue.value.items.isEmpty()) -1 else index.coerceIn(queue.value.items.indices)
                } ?: queue.value.currentIndex,
                positionMs = positionMs?.coerceAtLeast(0) ?: queue.value.positionMs,
                playWhenReady = playWhenReady ?: queue.value.playWhenReady,
                repeatMode = repeatMode ?: queue.value.repeatMode,
                shuffleEnabled = shuffleEnabled ?: queue.value.shuffleEnabled,
            ),
        )
    }

    override suspend fun recoveryState(): QueueRecoveryState = recovery

    private fun setAndSave(snapshot: QueueSnapshot) {
        val next = normalize(QueueRecoveryState(snapshot, recovery.shuffleOrder))
        storage.saveRecovery(next)
        recovery = next
        queue.value = next.snapshot
    }

    private fun normalize(state: QueueRecoveryState): QueueRecoveryState {
        val occurrenceIds = state.snapshot.items.map { it.id }
        val validPersisted = state.shuffleOrder.filter { it in occurrenceIds }.distinct()
        val missing = occurrenceIds.filterNot { it in validPersisted }
        val order = if (state.snapshot.shuffleEnabled) {
            validPersisted + createShuffleOrder(missing)
        } else {
            validPersisted
        }
        return state.copy(shuffleOrder = order)
    }
}
