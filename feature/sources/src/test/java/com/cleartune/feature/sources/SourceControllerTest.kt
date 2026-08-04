package com.cleartune.feature.sources

import com.cleartune.core.contracts.SourceRepository
import com.cleartune.core.model.MusicSource
import com.cleartune.core.model.SourceId
import com.cleartune.core.model.SourceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CancellationException
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
}

private class FakeSourceRepository(vararg sources: MusicSource) : SourceRepository {
    private val values = MutableStateFlow(sources.toList())
    override fun observeSources(): Flow<List<MusicSource>> = values
    override suspend fun getSource(sourceId: SourceId): MusicSource? = values.value.firstOrNull { it.id == sourceId }
}

private class FakeSourceActions(private val source: MusicSource) : SourceActionPort {
    val calls = mutableListOf<String>()
    var testFailure: SourceFailure? = null
    var cancelTest = false
    var receivedPassword: CharArray? = null
    override suspend fun test(draft: SourceDraft) {
        calls += "test"
        receivedPassword = draft.password
        if (cancelTest) throw CancellationException("stop")
        testFailure?.let { throw SourceActionException(it) }
    }
    override suspend fun save(draft: SourceDraft): MusicSource { calls += "save"; return source }
    override suspend fun delete(sourceId: SourceId) { calls += "delete" }
    override suspend fun sync(sourceId: SourceId) { calls += "sync" }
    override suspend fun browse(sourceId: SourceId, relativePath: String): List<SourceBrowseItem> {
        calls += "browse"
        return listOf(SourceBrowseItem("album", "album", isDirectory = true))
    }
}
