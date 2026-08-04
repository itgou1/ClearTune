package com.cleartune.app

import android.content.Context
import androidx.room.withTransaction
import com.cleartune.core.contracts.CredentialStore
import com.cleartune.core.contracts.SourceRepository
import com.cleartune.core.database.ClearTuneDatabase
import com.cleartune.core.model.DownloadState
import com.cleartune.core.model.MusicSource
import com.cleartune.core.model.SourceId
import com.cleartune.core.model.SourceType
import com.cleartune.core.network.WebDavUrlPolicy
import com.cleartune.data.webdav.DirectoryListingClient
import com.cleartune.data.webdav.RemoteFingerprint
import com.cleartune.data.webdav.SyncFailure
import com.cleartune.data.webdav.ValidatedWebDavSource
import com.cleartune.data.webdav.WebDavProtocolException
import com.cleartune.data.webdav.WebDavSourceDraft
import com.cleartune.data.webdav.WebDavSourceManager
import com.cleartune.data.webdav.WebDavSyncCheckpoint
import com.cleartune.data.webdav.WebDavSyncPort
import com.cleartune.data.webdav.WorkManagerWebDavSyncScheduler
import com.cleartune.feature.sources.SourceActionException
import com.cleartune.feature.sources.SourceActionPort
import com.cleartune.feature.sources.SourceBrowseItem
import com.cleartune.feature.sources.SourceDraft
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

object SourceOriginMatcher {
    fun match(sources: List<MusicSource>, rawUrl: String): MusicSource? {
        val candidate = rawUrl.toHttpUrlOrNull() ?: return null
        return sources.asSequence()
            .filter { it.enabled && it.type == SourceType.WEBDAV && !it.baseUrl.isNullOrBlank() }
            .mapNotNull { source ->
                val base = runCatching {
                    WebDavUrlPolicy.normalizeBaseUrl(requireNotNull(source.baseUrl), source.allowCleartext)
                }.getOrNull() ?: return@mapNotNull null
                source.takeIf { WebDavUrlPolicy.isInBaseSubtree(base, candidate) }
                    ?.let { it to base.pathSegments.dropLastWhile(String::isEmpty).size }
            }
            .maxByOrNull { it.second }
            ?.first
    }
}

interface WebDavCheckpointStore {
    suspend fun load(sourceId: SourceId): WebDavSyncCheckpoint?
    suspend fun save(checkpoint: WebDavSyncCheckpoint)
    suspend fun clear(sourceId: SourceId)
}

class SharedPreferencesWebDavCheckpointStore(context: Context) : WebDavCheckpointStore {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    override suspend fun load(sourceId: SourceId): WebDavSyncCheckpoint? = mutex.withLock {
        preferences.getString(sourceId.value, null)?.let(::decode)
            ?.takeIf { it.sourceId == sourceId }
    }

    override suspend fun save(checkpoint: WebDavSyncCheckpoint) = mutex.withLock {
        preferences.edit().putString(checkpoint.sourceId.value, encode(checkpoint)).commit().also(::check)
        Unit
    }

    override suspend fun clear(sourceId: SourceId) = mutex.withLock {
        preferences.edit().remove(sourceId.value).commit().also(::check)
        Unit
    }

    private fun encode(checkpoint: WebDavSyncCheckpoint) = JSONObject()
        .put("sourceId", checkpoint.sourceId.value)
        .put("pending", JSONArray(checkpoint.pendingDirectories))
        .put("visited", JSONArray(checkpoint.visitedDirectories.toList()))
        .put("retained", JSONArray(checkpoint.retainedSourceKeys.toList()))
        .put("discovered", checkpoint.discoveredTracks)
        .put(
            "failures",
            JSONArray().apply {
                checkpoint.failures.forEach { failure ->
                    put(JSONObject().put("directory", failure.relativeDirectory).put("retryable", failure.retryable))
                }
            },
        )
        .toString()

    private fun decode(raw: String): WebDavSyncCheckpoint? = runCatching {
        val json = JSONObject(raw)
        WebDavSyncCheckpoint(
            sourceId = SourceId(json.getString("sourceId")),
            pendingDirectories = json.getJSONArray("pending").strings(),
            visitedDirectories = json.getJSONArray("visited").strings().toSet(),
            retainedSourceKeys = json.getJSONArray("retained").strings().toSet(),
            discoveredTracks = json.optInt("discovered", 0).coerceAtLeast(0),
            failures = buildList {
                val failures = json.getJSONArray("failures")
                repeat(failures.length()) { index ->
                    val failure = failures.getJSONObject(index)
                    add(SyncFailure(failure.getString("directory"), failure.optBoolean("retryable", false)))
                }
            },
        )
    }.getOrNull()

    private fun JSONArray.strings(): List<String> = buildList {
        repeat(length()) { index -> add(getString(index)) }
    }

    private companion object { const val PREFERENCES = "webdav_sync_checkpoints" }
}

class RoomWebDavPersistenceAdapter(
    private val database: ClearTuneDatabase,
    private val sources: SourceRepository,
    private val checkpoints: WebDavCheckpointStore,
) : WebDavSyncPort {
    override suspend fun loadSource(sourceId: SourceId): MusicSource? = sources.getSource(sourceId)
    override suspend fun loadCheckpoint(sourceId: SourceId): WebDavSyncCheckpoint? = checkpoints.load(sourceId)
    override suspend fun saveCheckpoint(checkpoint: WebDavSyncCheckpoint) = checkpoints.save(checkpoint)
    override suspend fun clearCheckpoint(sourceId: SourceId) = checkpoints.clear(sourceId)

    override suspend fun remoteFingerprint(sourceId: SourceId, sourceKey: String): RemoteFingerprint? =
        database.libraryWriteDao().locationIncludingUnavailable(sourceId.value, sourceKey)
            ?.let { RemoteFingerprint(it.sizeBytes, it.etag, it.available) }

    override suspend fun markUpdateAvailable(sourceId: SourceId, sourceKey: String) {
        database.withTransaction {
            val location = database.libraryWriteDao()
                .locationIncludingUnavailable(sourceId.value, sourceKey) ?: return@withTransaction
            val download = database.downloadDao().downloadForTrack(location.trackId) ?: return@withTransaction
            if (download.state == DownloadState.COMPLETED.name) {
                database.downloadDao().upsert(
                    download.copy(
                        state = DownloadState.UPDATE_AVAILABLE.name,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }
}

class WebDavSourceActionAdapter(
    private val sources: SourceRepository,
    private val manager: WebDavSourceManager,
    private val client: DirectoryListingClient,
    private val scheduler: WorkManagerWebDavSyncScheduler,
    private val remover: RoomWebDavSourceRemovalCoordinator,
    private val testedBrowser: TestedWebDavBrowser,
) : SourceActionPort {
    private val validations = Collections.synchronizedMap(
        IdentityHashMap<SourceDraft, ValidatedWebDavSource>(),
    )

    override suspend fun test(draft: SourceDraft) = classified {
        validations[draft] = manager.test(draft.toWebDavDraft())
    }

    override suspend fun save(draft: SourceDraft): MusicSource = classified {
        val validation = validations.remove(draft) ?: throw IllegalArgumentException("Test the connection before saving")
        val source = manager.save(draft.toWebDavDraft(), validation)
        try {
            scheduler.enqueue(source.id)
        } catch (failure: Exception) {
            throw SourceActionException(
                com.cleartune.feature.sources.SourceFailure(
                    code = "schedule_failed",
                    message = "Source saved. Retry sync from the source screen.",
                    retryable = true,
                ),
            ).also { it.addSuppressed(failure) }
        }
        source
    }

    override suspend fun browseTested(draft: SourceDraft, relativePath: String): List<SourceBrowseItem> = classified {
        val validation = validations[draft] ?: throw IllegalArgumentException("Test the connection before browsing")
        testedBrowser.browse(validation.source, draft, relativePath)
    }

    override suspend fun selectRoot(draft: SourceDraft, relativePath: String): SourceDraft = classified {
        val validation = validations.remove(draft) ?: throw IllegalArgumentException("Test the connection before selecting a root")
        val base = WebDavUrlPolicy.normalizeBaseUrl(requireNotNull(validation.source.baseUrl), draft.allowCleartext)
        val selectedUrl = base.newBuilder().apply {
            relativePath.split('/').filter(String::isNotBlank).forEach(::addPathSegment)
            if (!build().encodedPath.endsWith('/')) addPathSegment("")
        }.build().toString()
        val selected = draft.copy(url = selectedUrl)
        validations[selected] = manager.rebase(draft.toWebDavDraft(), validation, selected.toWebDavDraft())
        selected
    }

    override suspend fun delete(sourceId: SourceId, deleteOfflineCopies: Boolean) = classified {
        requireNotNull(sources.getSource(sourceId)) { "Source not found" }
        remover.remove(sourceId, deleteOfflineCopies)
    }

    override suspend fun sync(sourceId: SourceId) = classified { scheduler.enqueue(sourceId) }

    override suspend fun browse(sourceId: SourceId, relativePath: String): List<SourceBrowseItem> = classified {
        val source = requireNotNull(sources.getSource(sourceId)) { "Source not found" }
        val base = WebDavUrlPolicy.normalizeBaseUrl(requireNotNull(source.baseUrl), source.allowCleartext)
        val builder = base.newBuilder()
        relativePath.split('/').filter(String::isNotBlank).forEach(builder::addPathSegment)
        if (!builder.build().encodedPath.endsWith('/')) builder.addPathSegment("")
        client.list(source, builder.build()).map { entry ->
            SourceBrowseItem(entry.name, entry.name, entry.isDirectory)
        }
    }

    private suspend fun <T> classified(block: suspend () -> T): T = try {
        block()
    } catch (failure: WebDavProtocolException) {
        throw SourceActionException(
            com.cleartune.feature.sources.SourceFailure(
                code = failure.failure.code.name.lowercase(),
                message = failure.failure.safeMessage,
                retryable = failure.failure.retryable,
            ),
        )
    }

    private fun SourceDraft.toWebDavDraft() = WebDavSourceDraft(
        name = name,
        url = url,
        username = username,
        password = password,
        allowCleartext = allowCleartext,
        sourceId = sourceId,
    )
}

fun interface TestedWebDavBrowser {
    suspend fun browse(
        source: MusicSource,
        draft: SourceDraft,
        relativePath: String,
    ): List<SourceBrowseItem>
}
