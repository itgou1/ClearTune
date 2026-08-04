package com.cleartune.playback

import com.cleartune.core.model.QueueCommand
import com.cleartune.core.model.QueueItem
import com.cleartune.core.model.QueueItemId
import com.cleartune.core.model.QueueSnapshot

object QueueReducer {
    fun reduce(
        snapshot: QueueSnapshot,
        command: QueueCommand,
        createId: () -> QueueItemId,
    ): QueueSnapshot = when (command) {
        is QueueCommand.Replace -> replace(snapshot, command, createId)
        is QueueCommand.AddNext -> addNext(snapshot, command, createId)
        is QueueCommand.AddLast -> addLast(snapshot, command, createId)
        is QueueCommand.Remove -> remove(snapshot, command)
        is QueueCommand.Move -> move(snapshot, command)
    }

    private fun replace(
        snapshot: QueueSnapshot,
        command: QueueCommand.Replace,
        createId: () -> QueueItemId,
    ): QueueSnapshot {
        val items = command.trackIds.map { QueueItem(createId(), it) }
        return snapshot.copy(
            items = items,
            currentIndex = if (items.isEmpty()) -1 else command.startIndex.coerceIn(items.indices),
            positionMs = 0,
        )
    }

    private fun addNext(
        snapshot: QueueSnapshot,
        command: QueueCommand.AddNext,
        createId: () -> QueueItemId,
    ): QueueSnapshot {
        val insertionIndex = if (snapshot.currentIndex >= 0) snapshot.currentIndex + 1 else 0
        val items = snapshot.items.toMutableList().apply {
            add(insertionIndex.coerceAtMost(size), QueueItem(createId(), command.trackId))
        }
        return snapshot.copy(
            items = items,
            currentIndex = if (snapshot.currentIndex == -1) 0 else snapshot.currentIndex,
        )
    }

    private fun addLast(
        snapshot: QueueSnapshot,
        command: QueueCommand.AddLast,
        createId: () -> QueueItemId,
    ): QueueSnapshot {
        val items = snapshot.items + QueueItem(createId(), command.trackId)
        return snapshot.copy(
            items = items,
            currentIndex = if (snapshot.currentIndex == -1) 0 else snapshot.currentIndex,
        )
    }

    private fun remove(snapshot: QueueSnapshot, command: QueueCommand.Remove): QueueSnapshot {
        val removedIndex = snapshot.items.indexOfFirst { it.id == command.queueItemId }
        if (removedIndex == -1) return snapshot

        val currentId = snapshot.items.getOrNull(snapshot.currentIndex)?.id
        val removedCurrent = currentId == command.queueItemId
        val items = snapshot.items.toMutableList().apply { removeAt(removedIndex) }
        val currentIndex = when {
            items.isEmpty() -> -1
            removedCurrent -> removedIndex.coerceAtMost(items.lastIndex)
            currentId != null -> items.indexOfFirst { it.id == currentId }
            else -> -1
        }
        return snapshot.copy(
            items = items,
            currentIndex = currentIndex,
            positionMs = if (removedCurrent) 0 else snapshot.positionMs,
        )
    }

    private fun move(snapshot: QueueSnapshot, command: QueueCommand.Move): QueueSnapshot {
        val oldIndex = snapshot.items.indexOfFirst { it.id == command.queueItemId }
        if (oldIndex == -1 || snapshot.items.size < 2) return snapshot

        val currentId = snapshot.items.getOrNull(snapshot.currentIndex)?.id
        val items = snapshot.items.toMutableList()
        val moved = items.removeAt(oldIndex)
        items.add(command.newIndex.coerceIn(0, items.size), moved)
        return snapshot.copy(
            items = items,
            currentIndex = currentId?.let { id -> items.indexOfFirst { it.id == id } } ?: -1,
        )
    }
}
