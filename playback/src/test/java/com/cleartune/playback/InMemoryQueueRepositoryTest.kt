package com.cleartune.playback

import com.cleartune.core.model.QueueCommand
import com.cleartune.core.model.QueueItemId
import com.cleartune.core.model.TrackId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class InMemoryQueueRepositoryTest {
    @Test
    fun commands_are_applied_atomically_in_order() = runTest {
        val ids = ArrayDeque(listOf("item-1", "item-2", "item-3"))
        val repository = InMemoryQueueRepository { QueueItemId(ids.removeFirst()) }

        repository.apply(QueueCommand.Replace(listOf(TrackId("one"), TrackId("two"))))
        repository.apply(QueueCommand.AddNext(TrackId("between")))

        val snapshot = repository.observeQueue().first()
        assertEquals(listOf("one", "between", "two"), snapshot.items.map { it.trackId.value })
        assertEquals(0, snapshot.currentIndex)
    }
}
