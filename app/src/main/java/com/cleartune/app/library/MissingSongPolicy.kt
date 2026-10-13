package com.cleartune.app.library

import com.cleartune.core.model.ClearTuneError
import com.cleartune.core.model.Song
import com.cleartune.core.network.RemoteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** A transport 404 can mean a broken endpoint or proxy, not a deleted song. */
internal suspend fun removeConfirmedMissingSong(
    songId: String,
    lookup: suspend (String) -> RemoteResult<Song>,
    remove: suspend (String) -> Unit,
): Boolean {
    val result = try {
        withTimeoutOrNull(5_000) { lookup(songId) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
    val error = (result as? RemoteResult.Failure)?.error
    if (error !is ClearTuneError.Server || error.code != 70) return false
    currentCoroutineContext().ensureActive()
    remove(songId)
    return true
}

internal fun missingSearchCandidates(local: List<Song>, remote: List<Song>): List<String> {
    val remoteIds = remote.mapTo(hashSetOf(), Song::id)
    return local.map(Song::id).distinct().filterNot(remoteIds::contains)
}
