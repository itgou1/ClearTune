package com.cleartune.core.player

import com.cleartune.core.model.PlaybackMode

object QueuePlanner {
    fun nextMode(mode: PlaybackMode): PlaybackMode = when (mode) {
        PlaybackMode.SEQUENTIAL -> PlaybackMode.REPEAT_ALL
        PlaybackMode.REPEAT_ALL -> PlaybackMode.REPEAT_ONE
        PlaybackMode.REPEAT_ONE -> PlaybackMode.SHUFFLE
        PlaybackMode.SHUFFLE -> PlaybackMode.SEQUENTIAL
    }

    fun <T> move(items: List<T>, from: Int, to: Int): List<T> {
        if (from !in items.indices || to !in items.indices || from == to) return items
        return items.toMutableList().apply { add(to, removeAt(from)) }
    }

    fun <T> remove(items: List<T>, index: Int): List<T> =
        if (index in items.indices) items.toMutableList().apply { removeAt(index) } else items
}
