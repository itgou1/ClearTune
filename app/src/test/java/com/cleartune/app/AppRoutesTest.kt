package com.cleartune.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.cleartune.core.contracts.QueueRepository
import com.cleartune.core.model.QueueCommand
import com.cleartune.core.model.QueueItem
import com.cleartune.core.model.QueueItemId
import com.cleartune.core.model.QueueSnapshot
import com.cleartune.core.model.TrackId
import com.cleartune.playback.PlaybackQueueStateWriter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test

class AppRoutesTest {
    @Test
    fun `library is the single root and there is no bottom navigation`() {
        assertEquals("library", AppRoutes.Library)
        assertFalse(AppRoutes.all.contains("bottom_navigation"))
        assertEquals(AppRoutes.all.size, AppRoutes.all.distinct().size)
    }

    @Test
    fun `playlist detail route round trips a saved identifier`() {
        val route = AppRoutes.playlistDetail("mix / favorites")

        assertEquals("mix / favorites", AppRoutes.playlistId(route))
        assertEquals(route, AppRoutes.restore(route))
    }

    @Test
    fun `unknown restored routes safely return to library`() {
        assertEquals(AppRoutes.Library, AppRoutes.restore("removed-destination"))
        assertTrue(AppRoutes.restorable.contains(AppRoutes.Player))
        assertTrue(AppRoutes.restorable.contains(AppRoutes.Settings))
    }

    @Test
    fun `selecting duplicate occurrence preserves ids and selects exact index before sync`() = runBlocking {
        val first = QueueItem(QueueItemId("first"), TrackId("same"))
        val second = QueueItem(QueueItemId("second"), TrackId("same"))
        val events = mutableListOf<String>()
        val repository = SelectableQueueRepository(
            QueueSnapshot(listOf(first, second), currentIndex = 0, positionMs = 777),
            events,
        )

        playQueueOccurrence(repository, repository, second.id, onQueueChanged = { events += "sync" })

        assertEquals(listOf(first.id, second.id), repository.state.value.items.map { it.id })
        assertEquals(1, repository.state.value.currentIndex)
        assertEquals(0, repository.state.value.positionMs)
        assertEquals(listOf("select", "sync"), events)
    }

    @Test
    fun `selecting current occurrence preserves playback position`() = runBlocking {
        val current = QueueItem(QueueItemId("current"), TrackId("track"))
        val events = mutableListOf<String>()
        val repository = SelectableQueueRepository(
            QueueSnapshot(listOf(current), currentIndex = 0, positionMs = 777),
            events,
        )

        playQueueOccurrence(repository, repository, current.id, onQueueChanged = { events += "sync" })

        assertEquals(777, repository.state.value.positionMs)
        assertEquals(listOf("select", "sync"), events)
    }
}

private class SelectableQueueRepository(
    initial: QueueSnapshot,
    private val events: MutableList<String>,
) : QueueRepository, PlaybackQueueStateWriter {
    val state = MutableStateFlow(initial)
    override fun observeQueue(): Flow<QueueSnapshot> = state
    override suspend fun apply(command: QueueCommand) = error("Queue replacement is not allowed")
    override suspend fun updatePlaybackState(
        currentIndex: Int?,
        positionMs: Long?,
        playWhenReady: Boolean?,
        repeatMode: com.cleartune.core.model.RepeatMode?,
        shuffleEnabled: Boolean?,
    ) {
        events += "select"
        state.value = state.value.copy(
            currentIndex = currentIndex ?: state.value.currentIndex,
            positionMs = positionMs ?: state.value.positionMs,
        )
    }
}
