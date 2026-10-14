package com.cleartune.core.player

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal open class AudioStartupPlayer(
    private val delegate: Player,
    private val gate: AudioStartupGate,
) : ForwardingPlayer(delegate) {
    override fun play() {
        if (delegate.playbackState == Player.STATE_IDLE && delegate.mediaItemCount > 0) delegate.prepare()
        gate.request(true)
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) = gate.request(playWhenReady)
    override fun pause() = gate.request(false)
    override fun stop() {
        gate.request(false)
        super.stop()
    }
    override fun clearMediaItems() {
        gate.request(false)
        super.clearMediaItems()
    }
}
