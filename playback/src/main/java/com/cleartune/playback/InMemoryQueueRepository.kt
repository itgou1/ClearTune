package com.cleartune.playback

import com.cleartune.core.contracts.QueueRepository
import com.cleartune.core.model.QueueCommand
import com.cleartune.core.model.QueueItemId
import com.cleartune.core.model.QueueSnapshot
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InMemoryQueueRepository(
    private val createId: () -> QueueItemId = { QueueItemId(UUID.randomUUID().toString()) },
) : QueueRepository {
    private val mutex = Mutex()
    private val queue = MutableStateFlow(QueueSnapshot())

    override fun observeQueue(): Flow<QueueSnapshot> = queue

    override suspend fun apply(command: QueueCommand) {
        mutex.withLock {
            queue.value = QueueReducer.reduce(queue.value, command, createId)
        }
    }
}
