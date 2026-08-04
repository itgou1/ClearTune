package com.cleartune.core.database

import org.junit.Assert.assertEquals
import org.junit.Test

class QueueShuffleOrderTest {
    @Test
    fun reconciliation_preserves_existing_shuffle_order_and_appends_new_items() {
        assertEquals(
            listOf("c", "a", "d"),
            reconcileShuffleOrder(existingOrder = listOf("c", "removed", "a"), queueOrder = listOf("a", "c", "d")),
        )
    }
}
