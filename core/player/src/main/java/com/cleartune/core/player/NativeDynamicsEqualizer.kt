package com.cleartune.core.player

import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import androidx.annotation.RequiresApi
import com.cleartune.core.datastore.EQUALIZER_FREQUENCIES_HZ
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** System-native EQ only: no compression, limiter, or application-side PCM conversion. */
@RequiresApi(28)
internal class NativeDynamicsEqualizer(sessionId: Int) : EqualizerEffect {
    private val effect: DynamicsProcessing
    private val cutoffs = EQUALIZER_FREQUENCIES_HZ.mapIndexed { index, frequency ->
        if (index == EQUALIZER_FREQUENCIES_HZ.lastIndex) 20_000f
        else sqrt(frequency.toDouble() * EQUALIZER_FREQUENCIES_HZ[index + 1]).toFloat()
    }

    init {
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
            true, cutoffs.size, false, 0, false, 0, false,
        ).setInputGainAllChannelsTo(0f)
        val eq = DynamicsProcessing.Eq(true, true, cutoffs.size)
        cutoffs.forEachIndexed { index, cutoff ->
            eq.setBand(index, DynamicsProcessing.EqBand(true, cutoff, 0f))
        }
        config.setPreEqAllChannelsTo(eq)
        effect = DynamicsProcessing(0, sessionId, config.build())
        try {
            check(effect.hasControl()) { "Native DynamicsProcessing control unavailable" }
        } catch (error: RuntimeException) {
            effect.release()
            throw error
        }
    }

    override var enabled: Boolean
        get() = effect.enabled
        set(value) {
            check(effect.setEnabled(value) == AudioEffect.SUCCESS) {
                "Native DynamicsProcessing enable=$value failed"
            }
        }
    override val centerFrequenciesHz = EQUALIZER_FREQUENCIES_HZ
    override val levelRange = -600..600
    override fun hasControl() = effect.hasControl()
    override fun getLevel(band: Int) =
        (effect.getPreEqBandByChannelIndex(0, band).gain * 100).roundToInt().toShort()
    override fun setLevel(band: Int, level: Short) = effect.setPreEqBandAllChannelsTo(
        band, DynamicsProcessing.EqBand(true, cutoffs[band], level.coerceIn(-600, 600) / 100f),
    )
    override fun release() = effect.release()
}
