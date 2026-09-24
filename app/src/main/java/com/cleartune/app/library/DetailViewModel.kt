package com.cleartune.app.library

import androidx.lifecycle.ViewModel
import com.cleartune.app.withResolvedArtwork
import com.cleartune.app.withResolvedArtistArtwork
import com.cleartune.core.model.ClearTuneError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal enum class DetailKind(val route: String) { ALBUM("album"), ARTIST("artist"), PLAYLIST("playlist") }
internal data class DetailTarget(val kind: DetailKind, val id: String) {
    val route: String get() = "${kind.route}/$id"
}

internal enum class DetailInvalidation { MISSING, DELETED }

internal interface DetailSource {
    fun observe(target: DetailTarget): Flow<DetailUiState>
    suspend fun refresh(target: DetailTarget): ClearTuneError?
}

internal class RepositoryDetailSource(private val repository: MusicRepository) : DetailSource {
    override fun observe(target: DetailTarget): Flow<DetailUiState> = when (target.kind) {
        DetailKind.ALBUM -> combine(repository.album(target.id), repository.albumSongs(target.id)) { album, songs ->
            DetailUiState(album = album?.withResolvedArtwork(songs), songs = songs)
        }
        DetailKind.ARTIST -> combine(repository.artist(target.id), repository.artistSongs(target.id)) { artist, songs ->
            DetailUiState(artist = artist?.withResolvedArtistArtwork(songs), songs = songs)
        }
        DetailKind.PLAYLIST -> combine(repository.playlist(target.id), repository.playlistSongs(target.id)) { playlist, songs ->
            DetailUiState(playlist = playlist, songs = songs)
        }
    }

    override suspend fun refresh(target: DetailTarget): ClearTuneError? = when (target.kind) {
        DetailKind.ALBUM -> repository.loadAlbumIfStale(target.id)
        DetailKind.ARTIST -> repository.loadArtistIfStale(target.id)
        DetailKind.PLAYLIST -> repository.openPlaylist(target.id)
    }
}

/** One instance per navigation entry, not per screen type or account-wide current detail. */
internal class DetailViewModel(
    val target: DetailTarget,
    source: DetailSource,
    accountJob: Job,
    invalidations: Flow<String>,
) : ViewModel() {
    // A popped entry AND a terminated account independently cancel this page's work.
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob(accountJob))
    private val _state = MutableStateFlow(DetailUiState(isLoading = true))
    val state = _state.asStateFlow()
    private val _invalidation = MutableStateFlow<DetailInvalidation?>(null)
    val invalidation = _invalidation.asStateFlow()
    val active: Boolean get() = scope.isActive
    private var refreshCompleted = false

    init {
        scope.coroutineContext[Job]!!.invokeOnCompletion {
            _state.value = DetailUiState()
            _invalidation.value = null
        }
        scope.launch {
            invalidations.collect { route ->
                if (route == target.route) _invalidation.value = DetailInvalidation.MISSING
            }
        }
        scope.launch {
            source.observe(target).collect { snapshot ->
                _state.update { current ->
                    snapshot.copy(
                        isLoading = !snapshot.hasContent() && !refreshCompleted,
                        errorMessage = current.errorMessage,
                    )
                }
            }
        }
        // Constructed once per entry: recomposition / predictive preview never restarts loading.
        scope.launch {
            val error = try {
                source.refresh(target)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ClearTuneError.Unexpected()
            }
            ensureActive()
            refreshCompleted = true
            _state.update { it.copy(isLoading = false, errorMessage = error?.userMessage.takeUnless { error.isNotFound() }) }
            if (error.isNotFound()) _invalidation.value = DetailInvalidation.MISSING
        }
    }

    fun markDeleted() {
        if (active) _invalidation.value = DetailInvalidation.DELETED
    }

    override fun onCleared() { scope.cancel() }

    private fun DetailUiState.hasContent(): Boolean =
        album != null || artist != null || playlist != null || songs.isNotEmpty()
}
