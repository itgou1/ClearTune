package com.cleartune.core.database

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceKeyRetentionTest {
    @Test
    fun more_than_sqlite_variable_limit_is_compared_in_memory_without_false_removals() {
        val existing = (0 until 1_200).map { "mediastore:$it" }

        assertEquals(emptySet<String>(), missingSourceKeys(existing, existing))
        assertEquals(
            (0 until 200).mapTo(linkedSetOf()) { "mediastore:$it" },
            missingSourceKeys(existing, existing.drop(200)),
        )
    }
}
