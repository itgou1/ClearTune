package com.cleartune.core.player

/** Main-thread gate: do not advance the first track while saved audio effects are loading. */
internal class AudioStartupGate(private val setPlaying: (Boolean) -> Unit) {
    private var ready = false
    private var requested = false
    private var closed = false

    fun request(play: Boolean) {
        if (closed) return
        requested = play
        if (ready || !play) setPlaying(play)
    }

    fun open() {
        if (closed || ready) return
        ready = true
        if (requested) setPlaying(true)
    }

    fun close() {
        closed = true
        requested = false
    }
}
