package com.cleartune.feature.library

import com.cleartune.core.contracts.PlaybackGateway
import com.cleartune.core.contracts.QueueRepository
import com.cleartune.core.model.PlaybackCommand
import com.cleartune.core.model.PlaybackState
import com.cleartune.core.model.PlaylistId
import com.cleartune.core.model.PlaylistSummary
import com.cleartune.core.model.QueueCommand
import com.cleartune.core.model.QueueSnapshot
import com.cleartune.core.model.TrackId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryProductActionsTest {
    @Test
    fun `play collection replaces complete queue syncs and starts playback`() = runBlocking {
        val queue = RecordingQueue()
        val playback = RecordingPlayback()
        var syncCount = 0
        val actions = LibraryPlaybackActions(queue, playback) { syncCount++ }

        actions.play(listOf(TrackId("one"), TrackId("two")), startIndex = 1)

        assertEquals(listOf(QueueCommand.Replace(listOf(TrackId("one"), TrackId("two")), 1)), queue.commands)
        assertEquals(1, syncCount)
        assertEquals(listOf(PlaybackCommand.Play), playback.commands)
    }

    @Test
    fun `track action order is unified and user playlists remain selectable`() {
        assertEquals(
            listOf(TrackAction.QUEUE_NEXT, TrackAction.QUEUE_LAST, TrackAction.PLAYLIST, TrackAction.FAVORITE, TrackAction.DOWNLOAD, TrackAction.DETAILS),
            TrackAction.values().toList(),
        )
        val playlists = listOf(PlaylistSummary(PlaylistId("mine"), "Mine"), PlaylistSummary(PlaylistId("other"), "Other"))
        assertEquals(listOf("mine", "other"), selectablePlaylists(playlists).map { it.id.value })
    }
}

private class RecordingQueue : QueueRepository {
    val commands = mutableListOf<QueueCommand>()
    override fun observeQueue(): Flow<QueueSnapshot> = MutableStateFlow(QueueSnapshot())
    override suspend fun apply(command: QueueCommand) { commands += command }
}

private class RecordingPlayback : PlaybackGateway {
    override val state = MutableStateFlow(PlaybackState())
    val commands = mutableListOf<PlaybackCommand>()
    override suspend fun dispatch(command: PlaybackCommand) { commands += command }
}
