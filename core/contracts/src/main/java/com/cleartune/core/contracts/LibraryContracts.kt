package com.cleartune.core.contracts

import com.cleartune.core.model.AlbumId
import com.cleartune.core.model.LibraryHome
import com.cleartune.core.model.LibraryMutation
import com.cleartune.core.model.MutationResult
import com.cleartune.core.model.SearchResults
import com.cleartune.core.model.SongQuery
import com.cleartune.core.model.TrackSummary
import kotlinx.coroutines.flow.Flow

interface LibraryRepository {
    fun observeLibraryHome(): Flow<LibraryHome>
    fun observeSongs(query: SongQuery): Flow<List<TrackSummary>>
    fun observeAlbumTracks(albumId: AlbumId): Flow<List<TrackSummary>>
    fun search(query: String): Flow<SearchResults>
}

interface LibraryWriteGateway {
    suspend fun applyLibraryMutation(mutation: LibraryMutation): MutationResult
}
