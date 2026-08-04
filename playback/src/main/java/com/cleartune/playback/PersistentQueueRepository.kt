package com.cleartune.playback

import com.cleartune.core.contracts.QueueRepository
import com.cleartune.core.model.QueueCommand
import com.cleartune.core.model.QueueItemId
import com.cleartune.core.model.QueueSnapshot
import com.cleartune.core.model.RepeatMode
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface QueueStorage {
    fun load(): QueueSnapshot?
    fun save(snapshot: QueueSnapshot)
}

interface PlaybackQueueStateWriter {
    suspend fun updatePlaybackState(
        positionMs: Long? = null,
        playWhenReady: Boolean? = null,
        repeatMode: RepeatMode? = null,
        shuffleEnabled: Boolean? = null,
    )
}

class PersistentQueueRepository(
    private val storage: QueueStorage,
    private val createId: () -> QueueItemId = { QueueItemId(UUID.randomUUID().toString()) },
) : QueueRepository, PlaybackQueueStateWriter {
    private val mutex = Mutex()
    private val queue = MutableStateFlow(storage.load() ?: QueueSnapshot())

    override fun observeQueue(): Flow<QueueSnapshot> = queue

    override suspend fun apply(command: QueueCommand) = mutex.withLock {
        setAndSave(QueueReducer.reduce(queue.value, command, createId))
    }

    override suspend fun updatePlaybackState(
        positionMs: Long?,
        playWhenReady: Boolean?,
        repeatMode: RepeatMode?,
        shuffleEnabled: Boolean?,
    ) = mutex.withLock {
        setAndSave(
            queue.value.copy(
                positionMs = positionMs?.coerceAtLeast(0) ?: queue.value.positionMs,
                playWhenReady = playWhenReady ?: queue.value.playWhenReady,
                repeatMode = repeatMode ?: queue.value.repeatMode,
                shuffleEnabled = shuffleEnabled ?: queue.value.shuffleEnabled,
            ),
        )
    }

    private fun setAndSave(snapshot: QueueSnapshot) {
        storage.save(snapshot)
        queue.value = snapshot
    }
}
