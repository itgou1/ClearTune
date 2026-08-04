package com.cleartune.feature.sources

import com.cleartune.core.contracts.SourceRepository
import com.cleartune.core.model.MusicSource
import com.cleartune.core.model.SourceId
import com.cleartune.core.model.SourceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceControllerTest {
    @Test
    fun `parses restorable list add edit root and browse routes`() {
        assertEquals(SourceRoute.List, SourceRoute.parse("sources"))
        assertEquals(SourceRoute.AddWebDav, SourceRoute.parse("sources/add-webdav"))
        assertEquals(SourceRoute.Edit(SourceId("s 1")), SourceRoute.parse("sources/s%201/edit"))
        assertEquals(SourceRoute.Root(SourceId("s1")), SourceRoute.parse("sources/s1"))
        assertEquals(SourceRoute.Browse(SourceId("s1"), "album/live"), SourceRoute.parse("sources/s1/browse/album%2Flive"))
    }

    @Test
    fun `tests saves syncs browses and deletes through source action port`() = runTest {
        val source = MusicSource(SourceId("s1"), "Remote", SourceType.WEBDAV, "https://music.example/dav/")
        val operations = FakeSourceActions(source)
        val controller = SourceController(FakeSourceRepository(source), operations)
        val form = WebDavFormState("Remote", "https://music.example/dav/", "alice", "secret")

        val tested = requireNotNull(controller.testConnection(form, null).value)
        assertEquals(source, controller.save(tested).value)
        assertNull(controller.requestSync(source.id).failure)
        assertEquals(listOf("album"), requireNotNull(controller.browse(source.id, "").value).map { it.name })
        assertNull(controller.delete(source.id).failure)

        assertEquals(listOf("test", "save", "sync", "browse", "delete"), operations.calls)
    }

    @Test
    fun `operation errors expose safe classified messages`() = runTest {
        val source = MusicSource(SourceId("s1"), "Remote", SourceType.WEBDAV, "https://music.example/dav/")
        val failure = SourceFailure("authentication", "Authentication failed", retryable = false)
        val operations = FakeSourceActions(source).apply { testFailure = failure }
        val controller = SourceController(FakeSourceRepository(source), operations)

        val result = controller.testConnection(
            WebDavFormState("Remote", "https://music.example/dav/", "alice", "bad"),
            null,
        )

        assertNull(result.value)
        assertEquals(failure, result.failure)
    }

    @Test
    fun `connection test cancellation clears temporary password`() = runTest {
        val source = MusicSource(SourceId("s1"), "Remote", SourceType.WEBDAV, "https://music.example/dav/")
        val operations = FakeSourceActions(source).apply { cancelTest = true }
        val controller = SourceController(FakeSourceRepository(source), operations)

        try {
            controller.testConnection(
                WebDavFormState("Remote", "https://music.example/dav/", "alice", "secret"),
                null,
            )
            error("Expected cancellation")
        } catch (_: CancellationException) {
            Unit
        }

        assertTrue(requireNotNull(operations.receivedPassword).all { it == '\u0000' })
    }

    @Test
    fun `editing and disposing zeroize successful test receipts`() = runTest {
        val source = source()
        val controller = SourceController(FakeSourceRepository(source), FakeSourceActions(source))
        val first = requireNotNull(controller.testConnection(form(), null).value)

        controller.abandon(first)

        assertTrue(first.draft.password.all { it == '\u0000' })
        val second = requireNotNull(controller.testConnection(form(), null).value)

        controller.close()

        assertTrue(second.draft.password.all { it == '\u0000' })
    }

    @Test
    fun `form edit rejects stale in flight connection receipt and clears its secret`() = runTest {
        val source = source()
        val operations = FakeSourceActions(source).apply { blockTest = true }
        val controller = SourceController(FakeSourceRepository(source), operations)

        val pending = async { controller.testConnection(form(), null) }
        operations.testStarted.await()
        controller.abandon(null)
        operations.testRelease.complete(Unit)
        val result = pending.await()

        assertEquals("stale_test", result.failure?.code)
        assertNull(result.value)
        assertTrue(requireNotNull(operations.receivedPassword).all { it == '\u0000' })
    }

    @Test
    fun `delete invokes navigation only after successful action`() = runTest {
        val source = source()
        val actions = FakeSourceActions(source)
        val controller = SourceController(FakeSourceRepository(source), actions)
        var navigations = 0

        actions.deleteFailure = SourceFailure("delete_denied", "Delete denied", false)
        val failed = controller.delete(source.id) { navigations++ }
        assertEquals("delete_denied", failed.failure?.code)
        assertEquals(0, navigations)

        actions.deleteFailure = null
        val succeeded = controller.delete(source.id) { navigations++ }
        assertNull(succeeded.failure)
        assertEquals(1, navigations)
    }

    @Test
    fun `browse sync failure is returned without hiding it behind refresh`() = runTest {
        val source = source()
        val failure = SourceFailure("authentication", "Authentication failed", false)
        val actions = FakeSourceActions(source).apply { syncFailure = failure }
        val controller = SourceController(FakeSourceRepository(source), actions)

        val result = controller.syncAndBrowse(source.id, "")

        assertEquals(failure, result.failure)
        assertEquals(listOf("sync"), actions.calls)
    }

    private fun source() = MusicSource(
        SourceId("s1"),
        "Remote",
        SourceType.WEBDAV,
        "https://music.example/dav/",
    )

    private fun form() = WebDavFormState("Remote", "https://music.example/dav/", "alice", "secret")
}

private class FakeSourceRepository(vararg sources: MusicSource) : SourceRepository {
    private val values = MutableStateFlow(sources.toList())
    override fun observeSources(): Flow<List<MusicSource>> = values
    override suspend fun getSource(sourceId: SourceId): MusicSource? = values.value.firstOrNull { it.id == sourceId }
}

private class FakeSourceActions(private val source: MusicSource) : SourceActionPort {
    val calls = mutableListOf<String>()
    var testFailure: SourceFailure? = null
    var deleteFailure: SourceFailure? = null
    var syncFailure: SourceFailure? = null
    var cancelTest = false
    var blockTest = false
    val testStarted = CompletableDeferred<Unit>()
    val testRelease = CompletableDeferred<Unit>()
    var receivedPassword: CharArray? = null
    override suspend fun test(draft: SourceDraft) {
        calls += "test"
        receivedPassword = draft.password
        if (blockTest) {
            testStarted.complete(Unit)
            testRelease.await()
        }
        if (cancelTest) throw CancellationException("stop")
        testFailure?.let { throw SourceActionException(it) }
    }
    override suspend fun save(draft: SourceDraft): MusicSource { calls += "save"; return source }
    override suspend fun delete(sourceId: SourceId) {
        calls += "delete"
        deleteFailure?.let { throw SourceActionException(it) }
    }
    override suspend fun sync(sourceId: SourceId) {
        calls += "sync"
        syncFailure?.let { throw SourceActionException(it) }
    }
    override suspend fun browse(sourceId: SourceId, relativePath: String): List<SourceBrowseItem> {
        calls += "browse"
        return listOf(SourceBrowseItem("album", "album", isDirectory = true))
    }
}
