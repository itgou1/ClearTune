package com.cleartune.app.player

import com.cleartune.core.database.PlayEventEntity
import com.cleartune.core.model.ClearTuneError
import com.cleartune.core.network.RemoteResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ScrobbleSyncTest {
    @Test fun retryKeepsOriginalTimeAndAcknowledgesOnlySuccess() = runBlocking {
        val event = PlayEventEntity(1, "moonlight", "COMPLETED", 1234)
        val queue = mutableListOf(event)
        val sent = mutableListOf<PlayEventEntity>()
        uploadPendingScrobbles({ queue }, { RemoteResult.Failure(ClearTuneError.Timeout()) }, {
            fail("A failed upload cannot be acknowledged")
        })
        assertEquals(listOf(event), queue)
        uploadPendingScrobbles({ after -> queue.filter { it.id > after } }, {
            sent += it
            RemoteResult.Success(Unit)
        }, { id -> queue.removeAll { it.id == id } })
        assertEquals(listOf(event), sent)
        assertTrue(queue.isEmpty())
    }

    @Test fun removedSongDoesNotBlockLaterListensOrLoopForever() = runBlocking {
        val queue = mutableListOf(
            PlayEventEntity(1, "removed", "COMPLETED", 100),
            PlayEventEntity(2, "moonlight", "COMPLETED", 200),
        )
        val sent = mutableListOf<String>()
        uploadPendingScrobbles({ after -> queue.filter { it.id > after }.take(1) }, {
            sent += it.songId
            if (it.id == 1L) RemoteResult.Failure(ClearTuneError.Server(70)) else RemoteResult.Success(Unit)
        }, { id -> queue.removeAll { it.id == id } })
        assertEquals(listOf("removed", "moonlight"), sent)
        assertEquals(listOf("removed"), queue.map { it.songId })
    }
}
