package com.cleartune.core.testing

import com.cleartune.core.contracts.CredentialStore
import com.cleartune.core.contracts.DownloadRepository
import com.cleartune.core.contracts.LibraryRepository
import com.cleartune.core.contracts.LibraryWriteGateway
import com.cleartune.core.contracts.PlaybackGateway
import com.cleartune.core.contracts.PlaybackLibraryRepository
import com.cleartune.core.contracts.PlaylistRepository
import com.cleartune.core.contracts.QueueRepository
import com.cleartune.core.contracts.SettingsRepository
import com.cleartune.core.contracts.SourceRepository
import com.cleartune.core.contracts.SourceWriteGateway
import com.cleartune.core.contracts.WebDavCredential
import com.cleartune.core.model.AlbumId
import com.cleartune.core.model.AppSettings
import com.cleartune.core.model.CredentialAlias
import com.cleartune.core.model.DownloadCommand
import com.cleartune.core.model.DownloadSummary
import com.cleartune.core.model.LibraryHome
import com.cleartune.core.model.LibraryMutation
import com.cleartune.core.model.MusicSource
import com.cleartune.core.model.MutationResult
import com.cleartune.core.model.PlayableTrack
import com.cleartune.core.model.PlaybackCommand
import com.cleartune.core.model.PlaybackState
import com.cleartune.core.model.PlaylistCommand
import com.cleartune.core.model.PlaylistSummary
import com.cleartune.core.model.QueueCommand
import com.cleartune.core.model.QueueSnapshot
import com.cleartune.core.model.SearchResults
import com.cleartune.core.model.SettingsCommand
import com.cleartune.core.model.SongQuery
import com.cleartune.core.model.SourceId
import com.cleartune.core.model.SourceMutation
import com.cleartune.core.model.TrackId
import com.cleartune.core.model.TrackSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeLibraryRepository(
    home: LibraryHome = LibraryHome(),
    songs: List<TrackSummary> = emptyList(),
    searchResults: SearchResults = SearchResults(),
) : LibraryRepository {
    private val homeFlow = MutableStateFlow(home)
    private val songsFlow = MutableStateFlow(songs)
    private val searchFlow = MutableStateFlow(searchResults)

    override fun observeLibraryHome(): Flow<LibraryHome> = homeFlow
    override fun observeSongs(query: SongQuery): Flow<List<TrackSummary>> = songsFlow
    override fun observeAlbumTracks(albumId: AlbumId): Flow<List<TrackSummary>> = songsFlow
    override fun search(query: String): Flow<SearchResults> = searchFlow

    fun emitHome(home: LibraryHome) { homeFlow.value = home }
    fun emitSongs(songs: List<TrackSummary>) { songsFlow.value = songs }
    fun emitSearch(results: SearchResults) { searchFlow.value = results }
}

class FakeLibraryWriteGateway(
    private val result: MutationResult = MutationResult(),
) : LibraryWriteGateway {
    val mutations = mutableListOf<LibraryMutation>()
    override suspend fun applyLibraryMutation(mutation: LibraryMutation): MutationResult {
        mutations += mutation
        return result
    }
}

class FakePlaybackLibraryRepository(
    private val tracks: MutableMap<TrackId, PlayableTrack> = mutableMapOf(),
) : PlaybackLibraryRepository {
    override suspend fun getPlayableTrack(trackId: TrackId): PlayableTrack? = tracks[trackId]
    fun put(track: PlayableTrack) { tracks[track.track.id] = track }
}

class FakeSourceRepository(
    sources: List<MusicSource> = emptyList(),
) : SourceRepository {
    private val sourceFlow = MutableStateFlow(sources)
    override fun observeSources(): Flow<List<MusicSource>> = sourceFlow
    override suspend fun getSource(sourceId: SourceId): MusicSource? = sourceFlow.value.firstOrNull { it.id == sourceId }
    fun emit(sources: List<MusicSource>) { sourceFlow.value = sources }
}

class FakeSourceWriteGateway(
    private val result: MutationResult = MutationResult(),
) : SourceWriteGateway {
    val mutations = mutableListOf<SourceMutation>()
    override suspend fun applySourceMutation(mutation: SourceMutation): MutationResult {
        mutations += mutation
        return result
    }
}

class InMemoryCredentialStore : CredentialStore {
    private val credentials = mutableMapOf<CredentialAlias, Pair<String, CharArray>>()
    override suspend fun put(alias: CredentialAlias, credential: WebDavCredential) {
        credentials[alias] = credential.username to credential.password.copyOf()
    }
    override suspend fun get(alias: CredentialAlias): WebDavCredential? = credentials[alias]?.let {
        WebDavCredential(it.first, it.second.copyOf())
    }
    override suspend fun delete(alias: CredentialAlias) {
        credentials.remove(alias)?.second?.fill('\u0000')
    }
}

class FakeDownloadRepository(
    downloads: List<DownloadSummary> = emptyList(),
) : DownloadRepository {
    private val downloadFlow = MutableStateFlow(downloads)
    val commands = mutableListOf<DownloadCommand>()
    override fun observeDownloads(): Flow<List<DownloadSummary>> = downloadFlow
    override suspend fun dispatch(command: DownloadCommand) { commands += command }
    fun emit(downloads: List<DownloadSummary>) { downloadFlow.value = downloads }
}

class FakePlaybackGateway(
    initialState: PlaybackState = PlaybackState(),
) : PlaybackGateway {
    private val stateFlow = MutableStateFlow(initialState)
    override val state: StateFlow<PlaybackState> = stateFlow
    val commands = mutableListOf<PlaybackCommand>()
    override suspend fun dispatch(command: PlaybackCommand) { commands += command }
    fun emit(state: PlaybackState) { stateFlow.value = state }
}

class FakeQueueRepository(
    queue: QueueSnapshot = QueueSnapshot(),
) : QueueRepository {
    private val queueFlow = MutableStateFlow(queue)
    val commands = mutableListOf<QueueCommand>()
    override fun observeQueue(): Flow<QueueSnapshot> = queueFlow
    override suspend fun apply(command: QueueCommand) { commands += command }
    fun emit(queue: QueueSnapshot) { queueFlow.value = queue }
}

class FakePlaylistRepository(
    playlists: List<PlaylistSummary> = emptyList(),
) : PlaylistRepository {
    private val playlistFlow = MutableStateFlow(playlists)
    val commands = mutableListOf<PlaylistCommand>()
    override fun observePlaylists(): Flow<List<PlaylistSummary>> = playlistFlow
    override suspend fun apply(command: PlaylistCommand) { commands += command }
    fun emit(playlists: List<PlaylistSummary>) { playlistFlow.value = playlists }
}

class FakeSettingsRepository(
    initialSettings: AppSettings = AppSettings(),
) : SettingsRepository {
    private val settingsFlow = MutableStateFlow(initialSettings)
    override val settings: Flow<AppSettings> = settingsFlow
    val commands = mutableListOf<SettingsCommand>()
    override suspend fun update(command: SettingsCommand) { commands += command }
    fun emit(settings: AppSettings) { settingsFlow.value = settings }
}
