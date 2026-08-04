package com.cleartune.data.webdav

import com.cleartune.core.contracts.LibraryWriteGateway
import com.cleartune.core.model.LibraryMutation
import com.cleartune.core.model.MusicSource
import com.cleartune.core.model.MutationResult
import com.cleartune.core.model.SourceId
import com.cleartune.core.model.SourceType
import com.cleartune.core.network.WebDavUrlPolicy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavSyncEngineTest {
    private val base = WebDavUrlPolicy.normalizeBaseUrl("https://music.example/dav/", false)
    private val source = MusicSource(SourceId("s1"), "Remote", SourceType.WEBDAV, base.toString())

    @Test
    fun traverses_breadth_first_avoids_cycles_and_persists_audio_candidates() = runTest {
        val calls = mutableListOf<String>()
        val client = DirectoryListingClient { _, directory ->
            calls += directory.encodedPath
            when (directory.encodedPath) {
                "/dav/" -> listOf(
                    WebDavEntry(base.resolve("album/")!!, "album", true, null, null),
                    WebDavEntry(base.resolve("root.mp3")!!, "root.mp3", false, 10, "r"),
                )
                "/dav/album/" -> listOf(
                    WebDavEntry(base, "parent", true, null, null),
                    WebDavEntry(base.resolve("album/song.flac")!!, "song.flac", false, 20, "s"),
                    WebDavEntry(base.resolve("album/cover.jpg")!!, "cover.jpg", false, 30, null),
                )
                else -> emptyList()
            }
        }
        val gateway = RecordingLibraryGateway()

        val report = WebDavSyncEngine(client, gateway).sync(source)

        assertEquals(listOf("/dav/", "/dav/album/"), calls)
        assertEquals(2, report.discoveredTracks)
        assertTrue(report.failures.isEmpty())
        val upserts = gateway.mutations.filterIsInstance<LibraryMutation.Upsert>()
        assertEquals(2, upserts.sumOf { it.tracks.size })
        assertTrue(gateway.mutations.last() is LibraryMutation.RetainSourceKeys)
    }

    @Test
    fun partial_failure_keeps_successes_and_skips_destructive_retain() = runTest {
        val broken = base.resolve("broken/")!!
        val client = DirectoryListingClient { _, directory ->
            if (directory == broken) error("secret server detail")
            listOf(
                WebDavEntry(broken, "broken", true, null, null),
                WebDavEntry(base.resolve("ok.ogg")!!, "ok.ogg", false, 7, null),
            )
        }
        val gateway = RecordingLibraryGateway()

        val report = WebDavSyncEngine(client, gateway).sync(source)

        assertEquals(1, report.discoveredTracks)
        assertEquals(listOf("broken"), report.failures.map { it.relativeDirectory })
        assertFalse(gateway.mutations.any { it is LibraryMutation.RetainSourceKeys })
    }
}

private class RecordingLibraryGateway : LibraryWriteGateway {
    val mutations = mutableListOf<LibraryMutation>()
    override suspend fun applyLibraryMutation(mutation: LibraryMutation): MutationResult {
        mutations += mutation
        return MutationResult()
    }
}
