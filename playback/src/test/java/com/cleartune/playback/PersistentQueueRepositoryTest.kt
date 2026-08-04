package com.cleartune.playback

import com.cleartune.core.model.QueueCommand
import com.cleartune.core.model.QueueItemId
import com.cleartune.core.model.RepeatMode
import com.cleartune.core.model.TrackId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentQueueRepositoryTest {
    @Test
    fun `queue occurrences and playback state survive repository recreation`() = runTest {
        val storage = MemoryQueueStorage()
        var nextId = 0
        val repository = PersistentQueueRepository(storage) { QueueItemId((++nextId).toString()) }
        repository.apply(QueueCommand.Replace(listOf(TrackId("same"), TrackId("same")), startIndex = 1))
        repository.updatePlaybackState(
            positionMs = 42_000,
            playWhenReady = true,
            repeatMode = RepeatMode.ALL,
            shuffleEnabled = true,
        )

        val restored = PersistentQueueRepository(storage).observeQueue().first()

        assertEquals(listOf("same", "same"), restored.items.map { it.trackId.value })
        assertEquals(2, restored.items.map { it.id }.distinct().size)
        assertEquals(1, restored.currentIndex)
        assertEquals(42_000, restored.positionMs)
        assertTrue(restored.playWhenReady)
        assertEquals(RepeatMode.ALL, restored.repeatMode)
        assertTrue(restored.shuffleEnabled)
    }
}

private class MemoryQueueStorage : QueueStorage {
    private var snapshot: com.cleartune.core.model.QueueSnapshot? = null
    override fun load(): com.cleartune.core.model.QueueSnapshot? = snapshot
    override fun save(snapshot: com.cleartune.core.model.QueueSnapshot) {
        this.snapshot = snapshot
    }
}
