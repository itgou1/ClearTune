package com.cleartune.core.database

import org.junit.Assert.assertEquals
import org.junit.Test

class FtsQueryTest {
    @Test
    fun user_text_is_encoded_as_safe_prefix_terms() {
        assertEquals("\"AC/DC\"* AND \"live\"*", ftsMatchQuery("  AC/DC live "))
        assertEquals("\"say\"* AND \"\"\"hello\"\"\"*", ftsMatchQuery("say \"hello\""))
        assertEquals(null, ftsMatchQuery("   "))
    }
}
