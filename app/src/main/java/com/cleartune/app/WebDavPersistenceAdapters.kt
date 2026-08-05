package com.cleartune.app

import android.content.Context
import androidx.room.withTransaction
import com.cleartune.core.contracts.CredentialStore
import com.cleartune.core.contracts.SourceRepository
import com.cleartune.core.database.ClearTuneDatabase
import com.cleartune.core.database.entity.SyncSessionEntity
import com.cleartune.core.model.DownloadState
import com.cleartune.core.model.MusicSource
import com.cleartune.core.model.MutationDisposition
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
import com.cleartune.data.webdav.WebDavSyncSession
import com.cleartune.data.webdav.WorkManagerWebDavSyncScheduler
import com.cleartune.feature.sources.SourceActionException
import com.cleartune.feature.sources.SourceActionPort
import com.cleartune.feature.sources.SourceBrowseItem
import com.cleartune.feature.sources.SourceDraft
import com.cleartune.feature.sources.SourceSyncPhase
import com.cleartune.feature.sources.SourceSyncStatus
import com.cleartune.feature.sources.SourceSyncStatusPort
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
    suspend fun save(checkpoint: WebDavSyncCheckpoint): MutationDisposition
    suspend fun clear(sourceId: SourceId)
    suspend fun retire(sourceId: SourceId)
}

class SharedPreferencesWebDavCheckpointStore(context: Context) : WebDavCheckpointStore {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    override suspend fun load(sourceId: SourceId): WebDavSyncCheckpoint? = mutex.withLock {
        if (preferences.getBoolean(retiredKey(sourceId), false)) return@withLock null
        preferences.getString(sourceId.value, null)?.let(::decode)
            ?.takeIf { it.sourceId == sourceId }
    }

    override suspend fun save(checkpoint: WebDavSyncCheckpoint) = mutex.withLock {
        if (preferences.getBoolean(retiredKey(checkpoint.sourceId), false)) {
            return@withLock MutationDisposition.SOURCE_RETIRED
        }
        preferences.edit().putString(checkpoint.sourceId.value, encode(checkpoint)).commit().also(::check)
        MutationDisposition.APPLIED
    }

    override suspend fun clear(sourceId: SourceId) = mutex.withLock {
        preferences.edit().remove(sourceId.value).commit().also(::check)
        Unit
    }

    override suspend fun retire(sourceId: SourceId) = mutex.withLock {
        preferences.edit()
            .remove(sourceId.value)
            .putBoolean(retiredKey(sourceId), true)
            .commit()
            .also(::check)
        Unit
    }

    private fun retiredKey(sourceId: SourceId) = "$RETIRED_PREFIX${sourceId.value}"

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

    private companion object {
        const val PREFERENCES = "webdav_sync_checkpoints"
        const val RETIRED_PREFIX = "retired:"
    }
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
    override suspend fun retireCheckpoint(sourceId: SourceId) = checkpoints.retire(sourceId)

    override suspend fun remoteFingerprint(sourceId: SourceId, sourceKey: String): RemoteFingerprint? =
        database.libraryWriteDao().locationIncludingUnavailable(sourceId.value, sourceKey)
            ?.let { RemoteFingerprint(it.sizeBytes, it.etag, it.available, it.modifiedEpochSeconds * 1_000) }

    override suspend fun markUpdateAvailable(sourceId: SourceId, sourceKey: String): MutationDisposition =
        database.withTransaction {
            if (!database.libraryWriteDao().isSourceActive(sourceId.value)) {
                return@withTransaction MutationDisposition.SOURCE_RETIRED
            }
            val location = database.libraryWriteDao()
                .locationIncludingUnavailable(sourceId.value, sourceKey)
                ?: return@withTransaction MutationDisposition.APPLIED
            val download = database.downloadDao().downloadForTrack(location.trackId)
                ?: return@withTransaction MutationDisposition.APPLIED
            if (download.state == DownloadState.COMPLETED.name) {
                database.downloadDao().upsert(
                    download.copy(
                        state = DownloadState.UPDATE_AVAILABLE.name,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    ),
                )
            }
            MutationDisposition.APPLIED
        }

    override suspend fun recordSyncSession(session: WebDavSyncSession) {
        database.libraryWriteDao().upsertSyncSession(
            SyncSessionEntity(
                id = session.id,
                sourceId = session.sourceId.value,
                startedAtEpochMs = session.startedAtEpochMs,
                completedAtEpochMs = session.completedAtEpochMs,
                phase = session.phase.name,
                processed = session.discoveredTracks,
                total = session.visitedDirectories,
                warningCount = session.failureCount,
                errorMessage = session.errorMessage,
            ),
        )
    }

    override suspend fun markSourceSynced(sourceId: SourceId, syncedAtEpochMs: Long): MutationDisposition =
        if (database.sourceDao().updateLastSynced(sourceId.value, syncedAtEpochMs) == 0) {
            MutationDisposition.SOURCE_RETIRED
        } else {
            MutationDisposition.APPLIED
        }
}

class RoomSourceSyncStatusAdapter(
    private val database: ClearTuneDatabase,
) : SourceSyncStatusPort {
    override fun observe(sourceId: SourceId): Flow<SourceSyncStatus?> =
        database.libraryWriteDao().observeLatestSyncSession(sourceId.value).map { session ->
            session?.let {
                SourceSyncStatus(
                    phase = runCatching { SourceSyncPhase.valueOf(it.phase) }.getOrDefault(SourceSyncPhase.FAILED),
                    discoveredTracks = it.processed.coerceAtLeast(0),
                    visitedDirectories = it.total.coerceAtLeast(0),
                    failureCount = it.warningCount.coerceAtLeast(0),
                    errorMessage = it.errorMessage,
                )
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

    override suspend fun cancelSync(sourceId: SourceId) = classified { scheduler.cancel(sourceId) }

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
