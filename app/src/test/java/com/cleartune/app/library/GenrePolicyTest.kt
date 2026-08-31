package com.cleartune.app.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenrePolicyTest {
    @Test
    fun hidesIdentifierShapedMetadata() {
        assertNull(normalizeGenreLabel("106212_10497"))
        assertNull(normalizeGenreLabel("4154790_420001"))
        assertNull(normalizeGenreLabel("e"))
    }

    @Test
    fun mergesKnownAliasesIntoStableChineseLabels() {
        assertEquals(
            listOf("流行", "华语流行", "蓝调"),
            normalizeGenreLabels(
                listOf("Pop", "流行", "华语流行音乐 【Chinese Pop Music】", "Blues"),
            ),
        )
        assertTrue(genreLabelsMatch("Pop", "流行"))
        assertFalse(genreLabelsMatch("Pop", "摇滚"))
    }
}
