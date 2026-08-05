package com.cleartune.core.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class QueueShuffleOrderTest {
    @Test
    fun reconciliation_preserves_existing_shuffle_order_and_appends_new_items() {
        assertEquals(
            listOf("c", "a", "d"),
            reconcileShuffleOrder(existingOrder = listOf("c", "removed", "a"), queueOrder = listOf("a", "c", "d")),
        )
    }

    @Test
    fun replacement_shuffle_starts_with_selected_occurrence_and_is_not_natural() {
        val natural = listOf("q1", "q2", "q3", "q4")

        val shuffled = replacementShuffleOrder(natural, selectedId = "q2")

        assertEquals("q2", shuffled.first())
        assertEquals(natural.toSet(), shuffled.toSet())
        assertEquals(natural.size, shuffled.size)
        assertNotEquals(natural, shuffled)
    }
}
