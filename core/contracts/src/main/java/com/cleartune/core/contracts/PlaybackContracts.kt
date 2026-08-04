package com.cleartune.core.contracts

import com.cleartune.core.model.PlayableTrack
import com.cleartune.core.model.PlaybackCommand
import com.cleartune.core.model.PlaybackState
import com.cleartune.core.model.QueueCommand
import com.cleartune.core.model.QueueSnapshot
import com.cleartune.core.model.TrackId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface PlaybackLibraryRepository {
    suspend fun getPlayableTrack(trackId: TrackId): PlayableTrack?
}

interface PlaybackGateway {
    val state: StateFlow<PlaybackState>
    suspend fun dispatch(command: PlaybackCommand)
}

interface QueueRepository {
    fun observeQueue(): Flow<QueueSnapshot>
    suspend fun apply(command: QueueCommand)
}
