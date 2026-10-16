package com.cleartune.core.player

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal open class AudioStartupPlayer(
    private val delegate: Player,
    private val gate: AudioStartupGate,
) : ForwardingPlayer(delegate) {
    override fun seekToNext() {
        if (isRadio()) seekToNextMediaItem() else super.seekToNext()
    }

    override fun seekToNextMediaItem() {
        if (isRadio()) switchRadio(1) else super.seekToNextMediaItem()
    }

    override fun seekToPreviousMediaItem() {
        if (isRadio()) switchRadio(-1) else super.seekToPreviousMediaItem()
    }

    private fun isRadio() = delegate.currentMediaItem?.mediaMetadata?.extras
        ?.getBoolean("com.cleartune.song.is_radio", false) == true

    private fun switchRadio(offset: Int) {
        if (delegate.mediaItemCount < 2) return
        val index = (delegate.currentMediaItemIndex + offset + delegate.mediaItemCount) % delegate.mediaItemCount
        delegate.seekToDefaultPosition(index)
        // A paused live source is stopped by the service; switching must reconnect it.
        play()
    }

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
