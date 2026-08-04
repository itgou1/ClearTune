package com.cleartune.playback

import com.cleartune.core.model.QueueCommand
import com.cleartune.core.model.QueueItem
import com.cleartune.core.model.QueueItemId
import com.cleartune.core.model.QueueSnapshot
import com.cleartune.core.model.TrackId
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueReducerTest {
    @Test
    fun replace_preserves_duplicate_tracks_with_unique_occurrence_ids() {
        val ids = ArrayDeque(listOf("occurrence-1", "occurrence-2"))
        val result = QueueReducer.reduce(
            snapshot = QueueSnapshot(),
            command = QueueCommand.Replace(listOf(TrackId("track"), TrackId("track")), startIndex = 1),
            createId = { QueueItemId(ids.removeFirst()) },
        )

        assertEquals(listOf("occurrence-1", "occurrence-2"), result.items.map { it.id.value })
        assertEquals(1, result.currentIndex)
    }

    @Test
    fun removing_current_item_advances_to_the_next_occurrence() {
        val result = QueueReducer.reduce(
            snapshot = snapshot(currentIndex = 1),
            command = QueueCommand.Remove(QueueItemId("item-2")),
            createId = { error("No ID should be created") },
        )

        assertEquals(listOf("item-1", "item-3"), result.items.map { it.id.value })
        assertEquals(1, result.currentIndex)
        assertEquals(0, result.positionMs)
    }

    @Test
    fun moving_item_keeps_the_same_current_occurrence_selected() {
        val result = QueueReducer.reduce(
            snapshot = snapshot(currentIndex = 1),
            command = QueueCommand.Move(QueueItemId("item-2"), newIndex = 0),
            createId = { error("No ID should be created") },
        )

        assertEquals(listOf("item-2", "item-1", "item-3"), result.items.map { it.id.value })
        assertEquals(0, result.currentIndex)
    }

    @Test
    fun add_next_inserts_after_current_item() {
        val result = QueueReducer.reduce(
            snapshot = snapshot(currentIndex = 0),
            command = QueueCommand.AddNext(TrackId("inserted")),
            createId = { QueueItemId("new-item") },
        )

        assertEquals(listOf("track-1", "inserted", "track-2", "track-3"), result.items.map { it.trackId.value })
        assertEquals(0, result.currentIndex)
    }

    private fun snapshot(currentIndex: Int) = QueueSnapshot(
        items = listOf(
            QueueItem(QueueItemId("item-1"), TrackId("track-1")),
            QueueItem(QueueItemId("item-2"), TrackId("track-2")),
            QueueItem(QueueItemId("item-3"), TrackId("track-3")),
        ),
        currentIndex = currentIndex,
        positionMs = 9_000,
    )
}
