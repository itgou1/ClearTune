package com.cleartune.core.player

import android.media.audiofx.Equalizer
import android.media.audiofx.AudioEffect

internal class SystemEqualizer(sessionId: Int) : EqualizerEffect {
    private val effect = Equalizer(0, sessionId)
    override var enabled: Boolean
        get() = effect.enabled
        set(value) {
            val status = effect.setEnabled(value)
            check(status == AudioEffect.SUCCESS) { "System equalizer enable=$value failed: $status" }
        }
    override val centerFrequenciesHz: List<Int>
        get() = List(effect.numberOfBands.toInt()) { (effect.getCenterFreq(it.toShort()) / 1000).coerceAtLeast(1) }
    override val levelRange: IntRange
        get() = effect.bandLevelRange.let { it[0].toInt()..it[1].toInt() }
    override fun hasControl() = effect.hasControl()
    override fun getLevel(band: Int) = effect.getBandLevel(band.toShort())
    override fun setLevel(band: Int, level: Short) = effect.setBandLevel(band.toShort(), level)
    override fun release() = effect.release()
}
