package com.cleartune.core.player

import android.media.audiofx.AudioEffect
import android.os.Build
import android.util.Log

/** Select by public API/effect availability, never by manufacturer or model. */
internal object NativeEqualizerFactory {
    fun create(sessionId: Int): EqualizerEffect {
        if (Build.VERSION.SDK_INT >= 28 && supportsDynamicsProcessing()) {
            try {
                return NativeDynamicsEqualizer(sessionId)
            } catch (error: RuntimeException) {
                Log.w(PlaybackService.TAG, "Native DynamicsProcessing unavailable; using legacy EQ", error)
            }
        }
        return SystemEqualizer(sessionId)
    }

    @androidx.annotation.RequiresApi(28)
    private fun supportsDynamicsProcessing(): Boolean = runCatching {
        AudioEffect.queryEffects().any { it.type == AudioEffect.EFFECT_TYPE_DYNAMICS_PROCESSING }
    }.getOrDefault(false)
}
