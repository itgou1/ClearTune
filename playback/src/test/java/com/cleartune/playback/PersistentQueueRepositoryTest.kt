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

    @Test
    fun `deterministic shuffle order survives repository recreation`() = runTest {
        val storage = MemoryQueueStorage()
        var nextId = 0
        val repository = PersistentQueueRepository(
            storage = storage,
            createId = { QueueItemId((++nextId).toString()) },
            createShuffleOrder = { occurrences -> occurrences.reversed() },
        )
        repository.apply(QueueCommand.Replace(listOf(TrackId("one"), TrackId("two"), TrackId("three"))))
        repository.updatePlaybackState(shuffleEnabled = true)

        val beforeRecreation = repository.recoveryState().shuffleOrder.map { it.value }
        val afterRecreation = PersistentQueueRepository(storage).recoveryState().shuffleOrder.map { it.value }

        assertEquals(listOf("3", "2", "1"), beforeRecreation)
        assertEquals(beforeRecreation, afterRecreation)
    }

    @Test
    fun `legacy storage derives the same shuffle order from persisted occurrences`() = runTest {
        val storage = LegacyMemoryQueueStorage()
        var nextId = 0
        val repository = PersistentQueueRepository(storage) { QueueItemId((++nextId).toString()) }
        repository.apply(QueueCommand.Replace((1..8).map { TrackId("track-$it") }))
        repository.updatePlaybackState(shuffleEnabled = true)

        val beforeRecreation = repository.recoveryState().shuffleOrder
        val afterRecreation = PersistentQueueRepository(storage).recoveryState().shuffleOrder

        assertEquals(beforeRecreation, afterRecreation)
    }
}

private class MemoryQueueStorage : QueueStorage {
    private var state: QueueRecoveryState? = null
    override fun loadRecovery(): QueueRecoveryState? = state
    override fun saveRecovery(state: QueueRecoveryState) {
        this.state = state
    }
    private var snapshot: com.cleartune.core.model.QueueSnapshot? = null
    override fun load(): com.cleartune.core.model.QueueSnapshot? = snapshot
    override fun save(snapshot: com.cleartune.core.model.QueueSnapshot) {
        this.snapshot = snapshot
        state = QueueRecoveryState(snapshot)
    }
}

private class LegacyMemoryQueueStorage : QueueStorage {
    private var snapshot: com.cleartune.core.model.QueueSnapshot? = null
    override fun load(): com.cleartune.core.model.QueueSnapshot? = snapshot
    override fun save(snapshot: com.cleartune.core.model.QueueSnapshot) { this.snapshot = snapshot }
}
