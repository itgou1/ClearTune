package com.cleartune.core.database

import androidx.room.withTransaction
import com.cleartune.core.contracts.PlaylistRepository
import com.cleartune.core.contracts.QueueRepository
import com.cleartune.core.contracts.SettingsRepository
import com.cleartune.core.database.dao.PlaybackDao
import com.cleartune.core.database.entity.AppSettingsEntity
import com.cleartune.core.database.entity.PlaybackQueueEntity
import com.cleartune.core.database.entity.PlaybackQueueItemEntity
import com.cleartune.core.database.entity.PlaybackStateEntity
import com.cleartune.core.database.entity.PlaylistEntity
import com.cleartune.core.database.entity.PlaylistTrackCrossRef
import com.cleartune.core.model.AppSettings
import com.cleartune.core.model.PlaylistCommand
import com.cleartune.core.model.PlaylistId
import com.cleartune.core.model.PlaylistSummary
import com.cleartune.core.model.QueueCommand
import com.cleartune.core.model.QueueItem
import com.cleartune.core.model.QueueItemId
import com.cleartune.core.model.QueueSnapshot
import com.cleartune.core.model.ReducedMotionMode
import com.cleartune.core.model.RepeatMode
import com.cleartune.core.model.SettingsCommand
import com.cleartune.core.model.ThemeMode
import com.cleartune.core.model.TrackId
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class RoomFavoritesRepository(
    private val database: ClearTuneDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.playlistDao()

    fun observeFavorites(): Flow<List<TrackId>> = dao.observeItems(FAVORITES_ID)
        .map { items -> items.map { TrackId(it.trackId) } }

    fun observeIsFavorite(trackId: TrackId): Flow<Boolean> =
        dao.observeContainsTrack(FAVORITES_ID, trackId.value)

    suspend fun setFavorite(trackId: TrackId, favorite: Boolean) = database.withTransaction {
        dao.upsertPlaylist(PlaylistEntity(FAVORITES_ID, FAVORITES_NAME, FAVORITES_CREATED_AT))
        val current = dao.items(FAVORITES_ID)
        val contains = current.any { it.trackId == trackId.value }
        if (contains == favorite) return@withTransaction
        val next = if (favorite) {
            current + PlaylistTrackCrossRef(
                id = "favorite:${trackId.value}",
                playlistId = FAVORITES_ID,
                trackId = trackId.value,
                position = current.size,
                addedAtEpochMs = clock(),
            )
        } else {
            current.filterNot { it.trackId == trackId.value }
        }.mapIndexed { index, item -> item.copy(position = index) }
        dao.clearItems(FAVORITES_ID)
        if (next.isNotEmpty()) dao.upsertItems(next)
    }

    suspend fun toggle(trackId: TrackId) {
        val favorite = dao.items(FAVORITES_ID).any { it.trackId == trackId.value }
        setFavorite(trackId, !favorite)
    }

    companion object {
        const val FAVORITES_ID = "favorites"
        const val FAVORITES_NAME = "Favorites"
        private const val FAVORITES_CREATED_AT = 0L
    }
}

class RoomQueueRepository(
    private val database: ClearTuneDatabase,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) : QueueRepository {
    private val dao = database.playbackDao()

    override fun observeQueue(): Flow<QueueSnapshot> = combine(
        dao.observeQueueItems(),
        dao.observePlaybackState(),
    ) { items, state ->
        val queueItems = items.map { QueueItem(QueueItemId(it.id), TrackId(it.trackId)) }
        QueueSnapshot(
            items = queueItems,
            currentIndex = state?.currentIndex?.coerceIn(-1, queueItems.lastIndex) ?: -1,
            positionMs = state?.positionMs?.coerceAtLeast(0) ?: 0,
            playWhenReady = state?.playWhenReady ?: false,
            repeatMode = state?.repeatMode?.let { value -> runCatching { RepeatMode.valueOf(value) }.getOrNull() }
                ?: RepeatMode.OFF,
            shuffleEnabled = state?.shuffleEnabled ?: false,
        )
    }

    override suspend fun apply(command: QueueCommand) = database.withTransaction {
        val existingItems = dao.queueItems().toMutableList()
        val existingState = dao.playbackState() ?: defaultPlaybackState()
        var currentIndex = existingState.currentIndex
        val existingCurrentItemId = existingItems.getOrNull(currentIndex)?.id
        val existingQueueOrder = existingItems.map(PlaybackQueueItemEntity::id)
        val existingShuffleOrder = reconcileShuffleOrder(
            existingOrder = existingState.shuffleOrder.split(SHUFFLE_SEPARATOR).filter(String::isNotBlank),
            queueOrder = existingQueueOrder,
        )
        val nextItems = when (command) {
            is QueueCommand.Replace -> {
                if (command.trackIds.isEmpty()) {
                    currentIndex = -1
                    mutableListOf()
                } else {
                    require(command.startIndex in command.trackIds.indices)
                    currentIndex = command.startIndex
                    command.trackIds.mapIndexedTo(mutableListOf()) { index, trackId ->
                        PlaybackQueueItemEntity(idFactory(), PlaybackDao.DEFAULT_QUEUE_ID, trackId.value, index)
                    }
                }
            }
            is QueueCommand.AddNext -> {
                val insertionIndex = (currentIndex + 1).coerceIn(0, existingItems.size)
                existingItems.add(
                    insertionIndex,
                    PlaybackQueueItemEntity(idFactory(), PlaybackDao.DEFAULT_QUEUE_ID, command.trackId.value, insertionIndex),
                )
                if (currentIndex < 0) currentIndex = 0
                existingItems
            }
            is QueueCommand.AddLast -> {
                existingItems += PlaybackQueueItemEntity(
                    idFactory(), PlaybackDao.DEFAULT_QUEUE_ID, command.trackId.value, existingItems.size,
                )
                if (currentIndex < 0) currentIndex = 0
                existingItems
            }
            is QueueCommand.Remove -> {
                val removedIndex = existingItems.indexOfFirst { it.id == command.queueItemId.value }
                if (removedIndex >= 0) {
                    existingItems.removeAt(removedIndex)
                    currentIndex = when {
                        existingItems.isEmpty() -> -1
                        removedIndex < currentIndex -> currentIndex - 1
                        removedIndex == currentIndex -> currentIndex.coerceAtMost(existingItems.lastIndex)
                        else -> currentIndex
                    }
                }
                existingItems
            }
            is QueueCommand.Move -> {
                require(command.newIndex in existingItems.indices)
                val oldIndex = existingItems.indexOfFirst { it.id == command.queueItemId.value }
                require(oldIndex >= 0)
                val currentItemId = existingItems.getOrNull(currentIndex)?.id
                val moved = existingItems.removeAt(oldIndex)
                existingItems.add(command.newIndex, moved)
                currentIndex = existingItems.indexOfFirst { it.id == currentItemId }
                existingItems
            }
        }.mapIndexed { index, item -> item.copy(position = index) }

        val nextQueueOrder = nextItems.map(PlaybackQueueItemEntity::id)
        val proposedShuffleOrder = when (command) {
            is QueueCommand.Replace -> if (existingState.shuffleEnabled) {
                replacementShuffleOrder(nextQueueOrder, nextItems.getOrNull(currentIndex)?.id)
            } else {
                nextQueueOrder
            }
            is QueueCommand.AddNext -> {
                val addedId = nextQueueOrder.first { it !in existingQueueOrder }
                val insertionIndex = (existingShuffleOrder.indexOf(existingCurrentItemId) + 1)
                    .coerceIn(0, existingShuffleOrder.size)
                existingShuffleOrder.toMutableList().apply { add(insertionIndex, addedId) }
            }
            is QueueCommand.AddLast -> existingShuffleOrder + nextQueueOrder.filterNot(existingQueueOrder::contains)
            is QueueCommand.Remove -> existingShuffleOrder.filterNot { it == command.queueItemId.value }
            is QueueCommand.Move -> existingShuffleOrder
        }
        val nextShuffleOrder = reconcileShuffleOrder(proposedShuffleOrder, nextQueueOrder)
        val removedCurrentItem = command is QueueCommand.Remove && command.queueItemId.value == existingCurrentItemId

        dao.upsertQueue(PlaybackQueueEntity(PlaybackDao.DEFAULT_QUEUE_ID, clock()))
        dao.clearQueueItems()
        if (nextItems.isNotEmpty()) dao.upsertQueueItems(nextItems)
        val replacing = command is QueueCommand.Replace
        dao.upsertPlaybackState(
            existingState.copy(
                queueId = PlaybackDao.DEFAULT_QUEUE_ID,
                currentIndex = currentIndex,
                positionMs = if (replacing || removedCurrentItem) 0 else existingState.positionMs,
                playWhenReady = if (replacing) false else existingState.playWhenReady,
                shuffleOrder = nextShuffleOrder.joinToString(SHUFFLE_SEPARATOR),
            ),
        )
    }

    private fun defaultPlaybackState() = PlaybackStateEntity(
        queueId = PlaybackDao.DEFAULT_QUEUE_ID,
        currentIndex = -1,
        positionMs = 0,
        playWhenReady = false,
        repeatMode = RepeatMode.OFF.name,
        shuffleEnabled = false,
        shuffleOrder = "",
    )

    private companion object { const val SHUFFLE_SEPARATOR = "\u001f" }
}

class RoomPlaylistRepository(
    private val database: ClearTuneDatabase,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) : PlaylistRepository {
    private val dao = database.playlistDao()

    override fun observePlaylists(): Flow<List<PlaylistSummary>> = dao.observePlaylists().map { rows ->
        rows.map { PlaylistSummary(PlaylistId(it.id), it.name, it.trackCount) }
    }

    override suspend fun apply(command: PlaylistCommand) {
        database.withTransaction {
            when (command) {
            is PlaylistCommand.Create -> dao.upsertPlaylist(
                PlaylistEntity(idFactory(), normalizedName(command.name), clock()),
            )
            is PlaylistCommand.Rename -> {
                val playlist = requireNotNull(dao.playlist(command.playlistId.value))
                dao.upsertPlaylist(playlist.copy(name = normalizedName(command.name)))
            }
            is PlaylistCommand.Delete -> dao.deletePlaylist(command.playlistId.value)
            is PlaylistCommand.AddTrack -> {
                requireNotNull(dao.playlist(command.playlistId.value))
                val position = dao.items(command.playlistId.value).size
                dao.upsertItems(
                    listOf(
                        PlaylistTrackCrossRef(
                            id = idFactory(),
                            playlistId = command.playlistId.value,
                            trackId = command.trackId.value,
                            position = position,
                            addedAtEpochMs = clock(),
                        ),
                    ),
                )
            }
            is PlaylistCommand.RemoveTrack -> dao.deleteItem(command.playlistId.value, command.playlistItemId.value)
            is PlaylistCommand.MoveTrack -> {
                val items = dao.items(command.playlistId.value).toMutableList()
                require(command.newIndex in items.indices)
                val oldIndex = items.indexOfFirst { it.id == command.playlistItemId.value }
                require(oldIndex >= 0)
                val moved = items.removeAt(oldIndex)
                items.add(command.newIndex, moved)
                dao.clearItems(command.playlistId.value)
                dao.upsertItems(items.mapIndexed { index, item -> item.copy(position = index) })
            }
            }
            Unit
        }
    }

    private fun normalizedName(value: String): String = value.trim().also {
        require(it.isNotEmpty() && it.length <= 100)
    }
}

class RoomSettingsRepository(private val database: ClearTuneDatabase) : SettingsRepository {
    private val dao = database.settingsDao()

    override val settings: Flow<AppSettings> = dao.observeSettings().map { entity -> entity?.toDomain() ?: AppSettings() }

    override suspend fun update(command: SettingsCommand) {
        val current = dao.settings()?.toDomain() ?: AppSettings()
        val updated = when (command) {
            is SettingsCommand.SetTheme -> current.copy(themeMode = command.mode)
            is SettingsCommand.SetReducedMotion -> current.copy(reducedMotionMode = command.mode)
        }
        dao.upsert(AppSettingsEntity(themeMode = updated.themeMode.name, reducedMotionMode = updated.reducedMotionMode.name))
    }
}

private fun AppSettingsEntity.toDomain() = AppSettings(
    themeMode = runCatching { ThemeMode.valueOf(themeMode) }.getOrDefault(ThemeMode.SYSTEM),
    reducedMotionMode = runCatching { ReducedMotionMode.valueOf(reducedMotionMode) }
        .getOrDefault(ReducedMotionMode.SYSTEM),
)

internal fun reconcileShuffleOrder(existingOrder: List<String>, queueOrder: List<String>): List<String> {
    val queueIds = queueOrder.toHashSet()
    val retained = existingOrder.filterTo(linkedSetOf()) { it in queueIds }
    queueOrder.forEach(retained::add)
    return retained.toList()
}

internal fun replacementShuffleOrder(queueOrder: List<String>, selectedId: String?): List<String> {
    if (queueOrder.isEmpty()) return emptyList()
    val selected = selectedId?.takeIf(queueOrder::contains)
    val remaining = queueOrder.filterNot { it == selected }.asReversed()
    return listOfNotNull(selected) + remaining
}
