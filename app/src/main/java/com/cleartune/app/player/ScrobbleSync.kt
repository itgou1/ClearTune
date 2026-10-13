package com.cleartune.app.player

import com.cleartune.core.database.PlayEventEntity
import com.cleartune.core.model.ClearTuneError
import com.cleartune.core.network.RemoteResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Keep original listening times when retrying. A failed upload remains durable for next time. */
internal suspend fun uploadPendingScrobbles(
    pending: suspend (Long) -> List<PlayEventEntity>,
    upload: suspend (PlayEventEntity) -> RemoteResult<Unit>,
    markSynced: suspend (Long) -> Unit,
) {
    var afterId = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val events = pending(afterId)
        if (events.isEmpty()) return
        for (event in events) {
            currentCoroutineContext().ensureActive()
            when (val result = upload(event)) {
                is RemoteResult.Success -> markSynced(event.id)
                is RemoteResult.Failure -> {
                    // A removed song must not block all later listens. Retain it locally.
                    val error = result.error
                    if (error !is ClearTuneError.Server || error.code != 70) return
                }
            }
            afterId = event.id
        }
    }
}
