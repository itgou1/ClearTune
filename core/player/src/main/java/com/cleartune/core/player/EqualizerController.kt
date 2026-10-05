package com.cleartune.core.player

import com.cleartune.core.datastore.EQUALIZER_FREQUENCIES_HZ
import com.cleartune.core.datastore.EqualizerSettings
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** All native operations are serialized off main, including creation and disposal. */
internal class EqualizerController(
    private val createEffect: (Int) -> EqualizerEffect,
    private val onHeadroom: (Float) -> Unit,
    private val onFailure: (Throwable) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    private val volumeDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val closed = AtomicBoolean(false)
    private var transition: Job? = null
    private var effect: EqualizerEffect? = null
    private var sessionId = 0
    private var headroom = 1f

    @Volatile var isAttached = false
        private set

    fun update(sessionId: Int, settings: EqualizerSettings, animate: Boolean): Job = scope.launch {
        if (closed.get()) return@launch
        transition?.cancel()
        // The returned parent job remains active until this transition completes.
        transition = launch {
            try {
                apply(sessionId, settings, animate)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                onFailure(error)
                detach()
                setHeadroom(1f)
            }
        }
    }

    fun close(): Job {
        closed.set(true)
        return scope.launch {
            transition?.cancel()
            detach()
            scope.cancel()
        }
    }

    private suspend fun apply(newSessionId: Int, settings: EqualizerSettings, animate: Boolean) {
        if (sessionId != newSessionId || newSessionId <= 0) {
            detach()
            sessionId = newSessionId
            setHeadroom(1f)
        }
        if (newSessionId <= 0 || (!settings.enabled && effect == null)) {
            setHeadroom(1f)
            return
        }
        // Store the handle on the owning dispatcher before any cancellable suspension.
        // A superseding update/close can then always dispose of a newly created effect.
        val current = effect ?: createEffect(newSessionId).also {
            effect = it
            isAttached = true
        }
        check(current.hasControl()) { "System equalizer control is held by another audio effect" }
        val frequencies = current.centerFrequenciesHz
        val range = current.levelRange
        if (!current.enabled) {
            if (!settings.enabled) {
                detach()
                setHeadroom(1f)
                return
            }
            // Enable a flat curve, then move towards the requested sound. Never
            // attach an arbitrary device preset at full gain or mute the player.
            frequencies.indices.forEach { current.setLevel(it, 0) }
            current.enabled = true
        }
        val start = frequencies.indices.map { current.getLevel(it).toInt() }
        val target = frequencies.map { frequency ->
            if (!settings.enabled) 0 else EqualizerMath.millibels(
                interpolatedEqualizerLevelDb(EQUALIZER_FREQUENCIES_HZ, settings.activeLevelsDb, frequency),
                range,
            ).toInt()
        }
        val steps = if (animate) TRANSITION_STEPS else 1
        repeat(steps) { step ->
            val fraction = (step + 1f) / steps
            val levels = start.indices.map { (start[it] + (target[it] - start[it]) * fraction).roundToInt() }
            val gain = EqualizerMath.headroomMultiplier(levels.map { it / 100f })
            // Attenuate before boosting; restore gain only after lowering bands.
            // Gain follows the current curve rather than dropping abruptly to
            // the destination headroom or passing through silence.
            if (gain < headroom) setHeadroom(gain)
            levels.forEachIndexed { index, level -> current.setLevel(index, level.toShort()) }
            if (gain != headroom) setHeadroom(gain)
            if (animate) delay(TRANSITION_DURATION_MS / steps)
        }
        if (!settings.enabled) {
            // Let the flat curve settle while audio continues at normal volume.
            if (animate) delay(FLAT_SETTLE_MS)
            detach()
        }
    }

    private suspend fun setHeadroom(value: Float) = withContext(volumeDispatcher) {
        if (!closed.get()) {
            headroom = value
            onHeadroom(value)
        }
    }

    private fun detach() {
        val old = effect
        effect = null
        isAttached = false
        if (old != null) {
            runCatching { old.enabled = false }.onFailure(onFailure)
            runCatching { old.release() }.onFailure(onFailure)
        }
    }

    private companion object {
        const val TRANSITION_STEPS = 12
        const val TRANSITION_DURATION_MS = 120L
        const val FLAT_SETTLE_MS = 20L
    }
}

internal interface EqualizerEffect {
    var enabled: Boolean
    val centerFrequenciesHz: List<Int>
    val levelRange: IntRange
    fun hasControl(): Boolean
    fun getLevel(band: Int): Short
    fun setLevel(band: Int, level: Short)
    fun release()
}
