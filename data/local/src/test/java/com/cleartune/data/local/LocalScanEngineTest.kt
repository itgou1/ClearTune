package com.cleartune.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalScanEngineTest {
    private val engine = LocalScanEngine()

    @Test
    fun diff_reports_add_update_remove_and_noop_by_stable_source_key() {
        val previous = listOf(
            snapshot("mediastore:1", title = "One"),
            snapshot("mediastore:2", title = "Old title"),
            snapshot("mediastore:3", title = "Removed"),
        )
        val incoming = listOf(
            snapshot("mediastore:1", title = "One"),
            snapshot("mediastore:2", title = "New title"),
            snapshot("mediastore:4", title = "Added"),
        )

        val diff = engine.diff(previous, incoming)

        assertEquals(listOf("mediastore:4"), diff.added.map(LocalAudioSnapshot::sourceKey))
        assertEquals(listOf("mediastore:2"), diff.updated.map(LocalAudioSnapshot::sourceKey))
        assertEquals(setOf("mediastore:3"), diff.removedSourceKeys)
        assertEquals(1, diff.unchangedCount)
    }

    @Test
    fun duplicate_incoming_keys_keep_the_first_row_and_emit_one_warning() {
        val diff = engine.diff(
            previous = emptyList(),
            incoming = listOf(
                snapshot("mediastore:1", title = "First"),
                snapshot("mediastore:1", title = "Second"),
            ),
        )

        assertEquals(listOf("First"), diff.accepted.map(LocalAudioSnapshot::title))
        assertEquals(listOf("Duplicate source key: mediastore:1"), diff.warnings)
    }

    private fun snapshot(sourceKey: String, title: String): LocalAudioSnapshot = LocalAudioSnapshot(
        sourceKey = sourceKey,
        contentUri = "content://$sourceKey",
        displayName = "$title.mp3",
        relativeFolder = "Music",
        title = title,
        album = null,
        artistNames = emptyList(),
        durationMs = 1_000,
        sizeBytes = 10,
        modifiedEpochSeconds = 1,
    )
}
