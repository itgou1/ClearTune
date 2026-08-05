package com.cleartune.data.webdav

import com.cleartune.core.contracts.LibraryWriteGateway
import com.cleartune.core.model.LibraryMutation
import com.cleartune.core.model.MusicSource
import com.cleartune.core.model.MutationDisposition
import com.cleartune.core.model.MutationResult
import com.cleartune.core.model.SourceId
import com.cleartune.core.model.SourceType
import com.cleartune.core.network.WebDavUrlPolicy
import com.cleartune.core.network.NetworkFailure
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
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

    @Test
    fun `duplicate directory leaf failure keeps its full identity and blocks retain`() = runTest {
        val failed = base.resolve("A/Music/")!!
        val successful = base.resolve("B/Music/")!!
        val client = DirectoryListingClient { _, directory ->
            when (directory.encodedPath) {
                "/dav/" -> listOf(
                    WebDavEntry(base.resolve("A/")!!, "A", true, null, null),
                    WebDavEntry(base.resolve("B/")!!, "B", true, null, null),
                )
                "/dav/A/" -> listOf(WebDavEntry(failed, "Music", true, null, null))
                "/dav/B/" -> listOf(WebDavEntry(successful, "Music", true, null, null))
                "/dav/A/Music/" -> error("listing denied")
                "/dav/B/Music/" -> listOf(
                    WebDavEntry(base.resolve("B/Music/song.mp3")!!, "song.mp3", false, 8, "v1"),
                )
                else -> emptyList()
            }
        }
        val gateway = RecordingLibraryGateway()

        val report = WebDavSyncEngine(client, gateway).sync(source)

        assertEquals(listOf("A/Music"), report.failures.map(SyncFailure::relativeDirectory))
        assertFalse(gateway.mutations.any { it is LibraryMutation.RetainSourceKeys })
    }

    @Test
    fun `resumes persisted breadth first frontier and checkpoints after each directory`() = runTest {
        val album = base.resolve("album/")!!
        val calls = mutableListOf<String>()
        val checkpoints = mutableListOf<WebDavSyncCheckpoint>()
        val client = DirectoryListingClient { _, directory ->
            calls += directory.encodedPath
            when (directory) {
                album -> listOf(WebDavEntry(base.resolve("album/song.flac")!!, "song.flac", false, 20, "s"))
                else -> error("root must not be revisited after process recreation")
            }
        }
        val checkpoint = WebDavSyncCheckpoint(
            sourceId = source.id,
            pendingDirectories = listOf(album.toString()),
            visitedDirectories = setOf(base.toString()),
            retainedSourceKeys = setOf("root.mp3"),
            discoveredTracks = 1,
        )

        val report = WebDavSyncEngine(client, RecordingLibraryGateway()).sync(source, checkpoint) {
            checkpoints += it
            MutationDisposition.APPLIED
        }

        assertEquals(listOf("/dav/album/"), calls)
        assertEquals(2, report.discoveredTracks)
        assertEquals(2, report.visitedDirectories)
        assertEquals(emptyList<String>(), checkpoints.last().pendingDirectories)
        assertEquals(setOf("root.mp3", "album/song.flac"), checkpoints.last().retainedSourceKeys)
    }

    @Test
    fun `unchanged etag and size skips enrichment and library upsert`() = runTest {
        var enrichmentCalls = 0
        val gateway = RecordingLibraryGateway()
        val engine = WebDavSyncEngine(
            client = DirectoryListingClient { _, _ ->
                listOf(WebDavEntry(base.resolve("same.mp3")!!, "same.mp3", false, 10, "v1"))
            },
            libraryWriteGateway = gateway,
            fingerprintLookup = RemoteFingerprintLookup { _, key ->
                if (key == "same.mp3") RemoteFingerprint(10, "v1") else null
            },
            metadataEnricher = WebDavMetadataEnricher { _, entry ->
                enrichmentCalls++
                EnrichedTrackMetadata(entry.name.substringBeforeLast('.'))
            },
        )

        val report = engine.sync(source)

        assertEquals(0, report.discoveredTracks)
        assertEquals(0, enrichmentCalls)
        assertFalse(gateway.mutations.any { it is LibraryMutation.Upsert })
    }

    @Test
    fun `validator-less same-size entry is enriched instead of assumed unchanged`() = runTest {
        var enrichmentCalls = 0
        val gateway = RecordingLibraryGateway()
        val engine = WebDavSyncEngine(
            client = DirectoryListingClient { _, _ ->
                listOf(WebDavEntry(base.resolve("same.mp3")!!, "same.mp3", false, 10, null, 2_000))
            },
            libraryWriteGateway = gateway,
            fingerprintLookup = RemoteFingerprintLookup { _, _ -> RemoteFingerprint(10, null) },
            metadataEnricher = WebDavMetadataEnricher { _, entry ->
                enrichmentCalls++
                EnrichedTrackMetadata(entry.name.substringBeforeLast('.'))
            },
        )

        engine.sync(source)

        assertEquals(1, enrichmentCalls)
        assertEquals(1, gateway.mutations.filterIsInstance<LibraryMutation.Upsert>().single().locations.size)
    }

    @Test
    fun `validator-less same-size entry compares persisted last modified`() = runTest {
        var enrichmentCalls = 0
        val gateway = RecordingLibraryGateway()
        WebDavSyncEngine(
            client = DirectoryListingClient { _, _ ->
                listOf(WebDavEntry(base.resolve("same.mp3")!!, "same.mp3", false, 10, null, 2_000))
            },
            libraryWriteGateway = gateway,
            fingerprintLookup = RemoteFingerprintLookup { _, _ ->
                RemoteFingerprint(10, null, modifiedEpochMs = 1_000)
            },
            metadataEnricher = WebDavMetadataEnricher { _, entry ->
                enrichmentCalls++
                EnrichedTrackMetadata(entry.name.substringBeforeLast('.'))
            },
        ).sync(source)

        assertEquals(1, enrichmentCalls)
        val location = gateway.mutations.filterIsInstance<LibraryMutation.Upsert>().single().locations.single()
        assertEquals(2_000L, location.modifiedEpochMs)
    }

    @Test
    fun `unavailable same fingerprint is reactivated with stable location identity`() = runTest {
        val gateway = RecordingLibraryGateway()
        val engine = WebDavSyncEngine(
            client = DirectoryListingClient { _, _ ->
                listOf(WebDavEntry(base.resolve("same.mp3")!!, "same.mp3", false, 10, "v1"))
            },
            libraryWriteGateway = gateway,
            fingerprintLookup = RemoteFingerprintLookup { _, _ -> RemoteFingerprint(10, "v1", available = false) },
        )

        val report = engine.sync(source)

        assertEquals(1, report.discoveredTracks)
        val upsert = gateway.mutations.filterIsInstance<LibraryMutation.Upsert>().single()
        assertEquals(1, upsert.locations.size)
        assertTrue(upsert.locations.single().available)
    }

    @Test(expected = CancellationException::class)
    fun `listing cancellation is never converted to a partial failure`() = runTest {
        WebDavSyncEngine(
            DirectoryListingClient { _, _ -> throw CancellationException("stop") },
            RecordingLibraryGateway(),
        ).sync(source)
    }

    @Test
    fun `metadata enrichment never exceeds configured concurrency`() = runTest {
        var active = 0
        var maximum = 0
        val release = CompletableDeferred<Unit>()
        val entries = (1..6).map { index ->
            WebDavEntry(base.resolve("$index.mp3")!!, "$index.mp3", false, 10, "v$index")
        }
        val engine = WebDavSyncEngine(
            client = DirectoryListingClient { _, _ -> entries },
            libraryWriteGateway = RecordingLibraryGateway(),
            metadataEnricher = WebDavMetadataEnricher { _, entry ->
                active++
                maximum = maxOf(maximum, active)
                if (active == 2) release.complete(Unit)
                release.await()
                delay(1)
                active--
                EnrichedTrackMetadata(entry.name.substringBeforeLast('.'))
            },
            maxEnrichmentConcurrency = 2,
        )

        engine.sync(source)

        assertEquals(2, maximum)
    }

    @Test
    fun `one malformed metadata entry falls back without aborting sibling sync`() = runTest {
        val gateway = RecordingLibraryGateway()
        val entries = listOf(
            WebDavEntry(base.resolve("bad.mp3")!!, "bad.mp3", false, 10, "bad"),
            WebDavEntry(base.resolve("good.mp3")!!, "good.mp3", false, 10, "good"),
        )
        val engine = WebDavSyncEngine(
            client = DirectoryListingClient { _, _ -> entries },
            libraryWriteGateway = gateway,
            metadataEnricher = WebDavMetadataEnricher { _, entry ->
                if (entry.name == "bad.mp3") throw IndexOutOfBoundsException("malformed tag")
                EnrichedTrackMetadata("Parsed Good")
            },
        )

        val report = engine.sync(source)

        assertEquals(2, report.discoveredTracks)
        val tracks = gateway.mutations.filterIsInstance<LibraryMutation.Upsert>().single().tracks
        assertEquals(listOf("bad", "Parsed Good"), tracks.map { it.title })
    }

    @Test(expected = CancellationException::class)
    fun `metadata cancellation still aborts sync`() = runTest {
        WebDavSyncEngine(
            client = DirectoryListingClient { _, _ ->
                listOf(WebDavEntry(base.resolve("stop.mp3")!!, "stop.mp3", false, 10, "v1"))
            },
            libraryWriteGateway = RecordingLibraryGateway(),
            metadataEnricher = WebDavMetadataEnricher { _, _ -> throw CancellationException("stop") },
        ).sync(source)
    }

    @Test
    fun `changed remote fingerprint marks completed download update available`() = runTest {
        val updates = mutableListOf<String>()
        WebDavSyncEngine(
            client = DirectoryListingClient { _, _ ->
                listOf(WebDavEntry(base.resolve("changed.mp3")!!, "changed.mp3", false, 11, "v2"))
            },
            libraryWriteGateway = RecordingLibraryGateway(),
            fingerprintLookup = RemoteFingerprintLookup { _, _ -> RemoteFingerprint(10, "v1") },
            updatePublisher = RemoteUpdatePublisher { _, sourceKey ->
                updates += sourceKey
                MutationDisposition.APPLIED
            },
        ).sync(source)

        assertEquals(listOf("changed.mp3"), updates)
    }

    @Test
    fun `enrichment failure falls back then publishes changed update availability`() = runTest {
        val updates = mutableListOf<String>()
        val engine = WebDavSyncEngine(
            client = DirectoryListingClient { _, _ ->
                listOf(WebDavEntry(base.resolve("changed.mp3")!!, "changed.mp3", false, 11, "v2"))
            },
            libraryWriteGateway = RecordingLibraryGateway(),
            fingerprintLookup = RemoteFingerprintLookup { _, _ -> RemoteFingerprint(10, "v1") },
            metadataEnricher = WebDavMetadataEnricher { _, _ -> error("metadata unavailable") },
            updatePublisher = RemoteUpdatePublisher { _, sourceKey ->
                updates += sourceKey
                MutationDisposition.APPLIED
            },
        )

        val report = engine.sync(source)

        assertEquals(1, report.discoveredTracks)
        assertEquals(listOf("changed.mp3"), updates)
    }

    @Test
    fun `library upsert failure does not publish update availability`() = runTest {
        val updates = mutableListOf<String>()
        val engine = WebDavSyncEngine(
            client = DirectoryListingClient { _, _ ->
                listOf(WebDavEntry(base.resolve("changed.mp3")!!, "changed.mp3", false, 11, "v2"))
            },
            libraryWriteGateway = object : LibraryWriteGateway {
                override suspend fun applyLibraryMutation(mutation: LibraryMutation): MutationResult =
                    throw IllegalStateException("database unavailable")
            },
            fingerprintLookup = RemoteFingerprintLookup { _, _ -> RemoteFingerprint(10, "v1") },
            updatePublisher = RemoteUpdatePublisher { _, sourceKey ->
                updates += sourceKey
                MutationDisposition.APPLIED
            },
        )

        runCatching { engine.sync(source) }

        assertTrue(updates.isEmpty())
    }

    @Test
    fun `retired source result stops publication and finalization without retry failure`() = runTest {
        val mutations = mutableListOf<LibraryMutation>()
        val updates = mutableListOf<String>()
        val checkpoints = mutableListOf<WebDavSyncCheckpoint>()
        val engine = WebDavSyncEngine(
            client = DirectoryListingClient { _, _ ->
                listOf(WebDavEntry(base.resolve("retired.mp3")!!, "retired.mp3", false, 11, "v2"))
            },
            libraryWriteGateway = object : LibraryWriteGateway {
                override suspend fun applyLibraryMutation(mutation: LibraryMutation): MutationResult {
                    mutations += mutation
                    return MutationResult(disposition = MutationDisposition.SOURCE_RETIRED)
                }
            },
            fingerprintLookup = RemoteFingerprintLookup { _, _ -> RemoteFingerprint(10, "v1") },
            updatePublisher = RemoteUpdatePublisher { _, key ->
                updates += key
                MutationDisposition.APPLIED
            },
        )

        val report = engine.sync(source, saveCheckpoint = {
            checkpoints += it
            MutationDisposition.APPLIED
        })

        assertTrue(report.retired)
        assertEquals(1, mutations.size)
        assertTrue(mutations.single() is LibraryMutation.Upsert)
        assertTrue(updates.isEmpty())
        assertTrue(checkpoints.isEmpty())
    }

    @Test
    fun `applied upsert then retirement before update publication stops finalization`() = runTest {
        val gateway = RecordingLibraryGateway()
        var updateAttempts = 0
        var checkpointAttempts = 0
        val engine = WebDavSyncEngine(
            client = DirectoryListingClient { _, _ ->
                listOf(WebDavEntry(base.resolve("late.mp3")!!, "late.mp3", false, 11, "v2"))
            },
            libraryWriteGateway = gateway,
            fingerprintLookup = RemoteFingerprintLookup { _, _ -> RemoteFingerprint(10, "v1") },
            updatePublisher = RemoteUpdatePublisher { _, _ ->
                updateAttempts += 1
                MutationDisposition.SOURCE_RETIRED
            },
        )

        val report = engine.sync(source, saveCheckpoint = {
            checkpointAttempts += 1
            MutationDisposition.APPLIED
        })

        assertTrue(report.retired)
        assertEquals(1, updateAttempts)
        assertEquals(0, checkpointAttempts)
        assertEquals(1, gateway.mutations.size)
        assertTrue(gateway.mutations.single() is LibraryMutation.Upsert)
    }

    @Test
    fun `applied update then retirement before checkpoint save stops finalization`() = runTest {
        val gateway = RecordingLibraryGateway()
        var updateAttempts = 0
        var checkpointAttempts = 0
        val engine = WebDavSyncEngine(
            client = DirectoryListingClient { _, _ ->
                listOf(WebDavEntry(base.resolve("late.mp3")!!, "late.mp3", false, 11, "v2"))
            },
            libraryWriteGateway = gateway,
            fingerprintLookup = RemoteFingerprintLookup { _, _ -> RemoteFingerprint(10, "v1") },
            updatePublisher = RemoteUpdatePublisher { _, _ ->
                updateAttempts += 1
                MutationDisposition.APPLIED
            },
        )

        val report = engine.sync(source, saveCheckpoint = {
            checkpointAttempts += 1
            MutationDisposition.SOURCE_RETIRED
        })

        assertTrue(report.retired)
        assertEquals(1, updateAttempts)
        assertEquals(1, checkpointAttempts)
        assertEquals(1, gateway.mutations.size)
        assertTrue(gateway.mutations.single() is LibraryMutation.Upsert)
    }

    @Test
    fun `transient listing failure checkpoints failed directory for a later retry`() = runTest {
        val checkpoints = mutableListOf<WebDavSyncCheckpoint>()
        val engine = WebDavSyncEngine(
            DirectoryListingClient { _, _ ->
                throw WebDavProtocolException(NetworkFailure.fromHttpStatus(503))
            },
            RecordingLibraryGateway(),
        )

        val report = engine.sync(source, saveCheckpoint = {
            checkpoints += it
            MutationDisposition.APPLIED
        })

        assertTrue(report.failures.single().retryable)
        assertEquals(listOf(base.toString()), checkpoints.last().pendingDirectories)
        assertFalse(base.toString() in checkpoints.last().visitedDirectories)
    }
}

private class RecordingLibraryGateway : LibraryWriteGateway {
    val mutations = mutableListOf<LibraryMutation>()
    override suspend fun applyLibraryMutation(mutation: LibraryMutation): MutationResult {
        mutations += mutation
        return MutationResult()
    }
}
