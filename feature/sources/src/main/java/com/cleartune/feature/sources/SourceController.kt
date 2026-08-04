package com.cleartune.feature.sources

import com.cleartune.core.contracts.SourceRepository
import com.cleartune.core.model.MusicSource
import com.cleartune.core.model.SourceId
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

sealed interface SourceRoute {
    data object List : SourceRoute
    data object AddWebDav : SourceRoute
    data class Edit(val sourceId: SourceId) : SourceRoute
    data class Root(val sourceId: SourceId) : SourceRoute
    data class Browse(val sourceId: SourceId, val relativePath: String) : SourceRoute
    data class Invalid(val rawRoute: String) : SourceRoute

    fun encoded(): String = when (this) {
        List -> "sources"
        AddWebDav -> "sources/add-webdav"
        is Edit -> "sources/${encode(sourceId.value)}/edit"
        is Root -> "sources/${encode(sourceId.value)}"
        is Browse -> buildString {
            append("sources/${encode(sourceId.value)}/browse")
            if (relativePath.isNotBlank()) append('/').append(encode(relativePath))
        }
        is Invalid -> rawRoute
    }

    companion object {
        fun parse(rawRoute: String): SourceRoute {
            val parts = rawRoute.trim('/').split('/')
            return try {
                when {
                    parts == listOf("sources") -> List
                    parts == listOf("sources", "add-webdav") -> AddWebDav
                    parts.size == 2 && parts[0] == "sources" -> Root(SourceId(decode(parts[1])))
                    parts.size == 3 && parts[0] == "sources" && parts[2] == "edit" -> Edit(SourceId(decode(parts[1])))
                    parts.size == 3 && parts[0] == "sources" && parts[2] == "browse" ->
                        Browse(SourceId(decode(parts[1])), "")
                    parts.size == 4 && parts[0] == "sources" && parts[2] == "browse" ->
                        Browse(SourceId(decode(parts[1])), decode(parts[3]))
                    else -> Invalid(rawRoute)
                }
            } catch (_: IllegalArgumentException) {
                Invalid(rawRoute)
            }
        }

        private fun encode(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
        private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }
}

data class SourceDraft(
    val sourceId: SourceId?,
    val name: String,
    val url: String,
    val username: String,
    val password: CharArray,
    val allowCleartext: Boolean,
)

data class SourceBrowseItem(val key: String, val name: String, val isDirectory: Boolean)
data class SourceFailure(val code: String, val message: String, val retryable: Boolean)
data class SourceResult<T>(val value: T? = null, val failure: SourceFailure? = null)
class TestedSourceDraft internal constructor(internal val draft: SourceDraft) : AutoCloseable {
    override fun close() = draft.password.fill('\u0000')
}

class SourceActionException(val failure: SourceFailure) : Exception(failure.message)

interface SourceActionPort {
    suspend fun test(draft: SourceDraft)
    suspend fun save(draft: SourceDraft): MusicSource
    suspend fun delete(sourceId: SourceId)
    suspend fun sync(sourceId: SourceId)
    suspend fun browse(sourceId: SourceId, relativePath: String): List<SourceBrowseItem>
}

class SourceController(
    private val sources: SourceRepository,
    private val actions: SourceActionPort,
) : AutoCloseable {
    private val revision = AtomicLong()
    private val receiptLock = Any()
    private val receipts = Collections.newSetFromMap(IdentityHashMap<TestedSourceDraft, Boolean>())

    suspend fun testConnection(state: WebDavFormState, sourceId: SourceId?): SourceResult<TestedSourceDraft> {
        val expectedRevision = revision.get()
        val draft = SourceDraft(
            sourceId,
            state.name,
            state.url,
            state.username,
            state.password.toCharArray(),
            state.allowCleartext,
        )
        val tested = action(draft.password) {
            actions.test(draft)
            Unit
        }
        tested.failure?.let { return SourceResult(failure = it) }
        synchronized(receiptLock) {
            if (revision.get() != expectedRevision) {
                draft.password.fill('\u0000')
                return SourceResult(failure = SourceFailure(STALE_TEST_CODE, "Source details changed", false))
            }
            return SourceResult(value = TestedSourceDraft(draft).also(receipts::add))
        }
    }

    suspend fun save(tested: TestedSourceDraft): SourceResult<MusicSource> = try {
        action(tested.draft.password) { actions.save(tested.draft) }
    } finally {
        synchronized(receiptLock) { receipts.remove(tested) }
        tested.close()
    }

    suspend fun delete(sourceId: SourceId, onSuccess: () -> Unit = {}): SourceResult<Unit> {
        val result = action { actions.delete(sourceId) }
        if (result.failure == null) onSuccess()
        return result
    }

    suspend fun requestSync(sourceId: SourceId): SourceResult<Unit> = action {
        requireNotNull(sources.getSource(sourceId)) { "Source not found" }
        actions.sync(sourceId)
    }

    suspend fun browse(sourceId: SourceId, relativePath: String): SourceResult<List<SourceBrowseItem>> = action {
        requireNotNull(sources.getSource(sourceId)) { "Source not found" }
        val normalized = relativePath.trim('/')
        require(normalized.split('/').none { it == "." || it == ".." }) { "Invalid browse path" }
        actions.browse(sourceId, normalized)
    }

    suspend fun syncAndBrowse(sourceId: SourceId, relativePath: String): SourceResult<List<SourceBrowseItem>> {
        val sync = requestSync(sourceId)
        sync.failure?.let { return SourceResult(failure = it) }
        return browse(sourceId, relativePath)
    }

    fun abandon(tested: TestedSourceDraft?) {
        synchronized(receiptLock) {
            revision.incrementAndGet()
            tested?.let {
                receipts.remove(it)
                it.close()
            }
        }
    }

    override fun close() {
        synchronized(receiptLock) {
            revision.incrementAndGet()
            receipts.forEach(TestedSourceDraft::close)
            receipts.clear()
        }
    }

    suspend fun form(sourceId: SourceId): WebDavFormState? = sources.getSource(sourceId)?.let { source ->
        WebDavFormState(name = source.name, url = source.baseUrl.orEmpty(), allowCleartext = source.allowCleartext)
    }

    private suspend fun <T> action(secret: CharArray? = null, block: suspend () -> T): SourceResult<T> = try {
        SourceResult(value = block())
    } catch (cancellation: CancellationException) {
        secret?.fill('\u0000')
        throw cancellation
    } catch (failure: SourceActionException) {
        secret?.fill('\u0000')
        SourceResult(failure = failure.failure)
    } catch (failure: IllegalArgumentException) {
        secret?.fill('\u0000')
        SourceResult(failure = SourceFailure("invalid_input", failure.message ?: "Invalid source details", false))
    } catch (_: Exception) {
        secret?.fill('\u0000')
        SourceResult(failure = SourceFailure("operation_failed", "Unable to complete source operation", true))
    }

    private companion object {
        const val STALE_TEST_CODE = "stale_test"
    }
}
