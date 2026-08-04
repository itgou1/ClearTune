package com.cleartune.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceRemovalLinearizationTest {
    @Test
    fun `external effects cannot start before the removal transaction commits`() = runBlocking {
        val transactionStarted = CompletableDeferred<Unit>()
        val allowCommit = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        val operation = async {
            commitSourceRemoval(
                transaction = {
                    events += "transaction-started"
                    transactionStarted.complete(Unit)
                    allowCommit.await()
                    events += "transaction-committed"
                    listOf("download-1")
                },
                afterCommit = { committed ->
                    events += "effects:${committed.single()}"
                },
            )
        }

        transactionStarted.await()
        assertEquals(listOf("transaction-started"), events)
        allowCommit.complete(Unit)

        assertEquals(listOf("download-1"), operation.await())
        assertEquals(
            listOf("transaction-started", "transaction-committed", "effects:download-1"),
            events,
        )
    }
}
