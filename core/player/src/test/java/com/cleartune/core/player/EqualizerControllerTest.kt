package com.cleartune.core.player

import com.cleartune.core.datastore.EqualizerPreset
import com.cleartune.core.datastore.EqualizerSettings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class EqualizerControllerTest {
    private val boosted = EqualizerSettings(true, EqualizerPreset.CUSTOM, listOf(6, 6, 4, 2, 0, -1, -2, -3, -5, -6))

    @Test fun legacyNativeBandsReceiveMappedCurveBeforeEnable() = fixture { f ->
        f.frequencies = listOf(31, 125, 500, 2000, 16000)
        f.controller.update(1, boosted, false).join()
        assertEquals(listOf(600, 400, 0, -200, -600), f.effects.single().levelsAtEnable)
        assertEquals(0.5011872f, f.effects.single().gainAtEnable, 0.0001f)
        f.controller.update(1, boosted.copy(enabled = false), false).join()
        assertTrue(f.controller.isAttached)
        assertFalse(f.controller.isEqualizing)
        assertEquals(1f, f.gains.last(), 0f)
    }

    @Test fun coldStartAppliesFinalHeadroomAndCurveBeforeEnablingEffect() = fixture { f ->
        f.controller.update(1, boosted, false).join()
        val effect = f.effects.single()
        assertEquals(listOf(600, 600, 400, 200, 0, -100, -200, -300, -500, -600), effect.levelsAtEnable)
        assertEquals(0.5011872f, effect.gainAtEnable, 0.0001f)
        assertEquals(listOf(1f, effect.gainAtEnable), f.gains)
    }

    @Test fun replacingSessionNeverRestoresFullGainOrRampsFromFlat() = fixture { f ->
        f.controller.update(1, boosted, false).join()
        val previousGain = f.gains.last()
        f.gains.clear()
        f.controller.update(2, boosted, true).join()
        assertTrue(f.gains.isNotEmpty())
        assertTrue(f.gains.all { it <= previousGain })
        assertEquals(listOf(600, 600, 400, 200, 0, -100, -200, -300, -500, -600), f.effects.last().levelsAtEnable)
        assertEquals(previousGain, f.effects.last().gainAtEnable, 0f)
        assertEquals(1, f.effects.first().releases)
    }

    @Test fun switchingNeverMutesOrReleasesTheSessionEffect() = fixture { f ->
        f.controller.update(1, boosted, true).join()
        assertEquals(0.5011872f, f.gains.last(), 0.0001f)
        f.controller.update(1, boosted.copy(enabled = false), true).join()
        assertTrue(f.controller.isAttached)
        assertFalse(f.controller.isEqualizing)
        assertTrue(f.gains.all { it >= 0.501f })
        assertTrue(f.gains.zipWithNext().all { (a, b) -> kotlin.math.abs(a - b) < 0.07f })
        assertEquals(1f, f.gains.last(), 0f)
        assertEquals(List(10) { 0 }, f.effects.single().levels.toList())
        assertEquals(0, f.effects.single().releases)
        assertTrue(f.effects.single().threads.all { it.substringBefore(" @") == "eq-worker" })
        assertTrue(f.volumeThreads.all { it.substringBefore(" @") == "eq-volume" })
        assertTrue(f.errors.isEmpty())
    }

    @Test fun flatPresetAndOffReuseOneUnityEffect() = fixture { f ->
        f.controller.update(1, EqualizerSettings(enabled = true), true).join()
        repeat(3) { f.controller.update(1, EqualizerSettings(), true).join() }
        assertTrue(f.gains.all { it == 1f })
        assertEquals(1, f.effects.size)
        assertEquals(0, f.effects.single().releases)
    }

    @Test fun returningToFlatAndTogglingKeepsOneUnityEffect() = fixture { f ->
        f.controller.update(1, boosted, false).join()
        f.controller.update(1, EqualizerSettings(enabled = true), true).join()
        assertTrue(f.controller.isAttached)
        assertFalse(f.controller.isEqualizing)
        assertEquals(0, f.effects.single().releases)
        assertEquals(List(10) { 0 }, f.effects.single().levels.toList())
        repeat(3) {
            f.controller.update(1, EqualizerSettings(), true).join()
            f.controller.update(1, EqualizerSettings(enabled = true), true).join()
        }
        f.controller.update(1, boosted.copy(customLevelsDb = List(10) { 0 }), true).join()
        assertEquals(1, f.effects.size)
        assertEquals(1f, f.gains.last(), 0f)
    }

    @Test fun rapidReversalKeepsLatestCurveAndDoesNotReleaseReenabledEffect() = fixture { f ->
        f.controller.update(1, boosted, false).join()
        f.controller.update(1, boosted.copy(enabled = false), true)
        delay(25)
        f.controller.update(1, boosted, true).join()
        assertTrue(f.controller.isAttached)
        assertEquals(0, f.effects.single().releases)
        assertEquals(listOf(600, 600, 400, 200, 0, -100, -200, -300, -500, -600), f.effects.single().levels.toList())
        f.controller.update(1, boosted.copy(enabled = false), true).join()
        assertTrue(f.controller.isAttached)
        assertFalse(f.controller.isEqualizing)
        assertEquals(1f, f.gains.last(), 0f)
        assertTrue(f.errors.isEmpty())
    }

    @Test fun sessionReplacementReleasesOldHandleAndDoesNotRestoreStaleSettings() = fixture { f ->
        f.controller.update(1, boosted, false).join()
        f.controller.update(2, boosted, false).join()
        assertEquals(listOf(1, 2), f.effects.map { it.session })
        assertEquals(1, f.effects.first().releases)
        f.controller.update(2, boosted.copy(enabled = false), true).join()
        assertEquals(listOf(1, 0), f.effects.map { it.releases })
        assertTrue(f.controller.isAttached)
        assertFalse(f.controller.isEqualizing)
        assertEquals(1f, f.gains.last(), 0f)
    }

    @Test fun closeDuringSlowNativeCreationDoesNotLeakOrBlockCaller() = fixture { f ->
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        f.onCreate = {
            entered.countDown()
            check(resume.await(2, TimeUnit.SECONDS))
        }
        f.controller.update(1, boosted, true)
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val closing = f.controller.close()
        resume.countDown()
        closing.join()
        assertFalse(f.controller.isAttached)
        assertEquals(1, f.effects.single().releases)
        assertTrue(f.errors.isEmpty())
    }

    @Test fun nativeFailureReleasesResourcesAndRestoresUnattenuatedPlayback() = fixture { f ->
        f.controller.update(1, boosted, false).join()
        f.effects.single().failWrites = true
        f.controller.update(1, boosted.copy(enabled = false), true).join()
        assertFalse(f.controller.isAttached)
        assertEquals(1, f.effects.single().releases)
        assertEquals(1f, f.gains.last(), 0f)
        assertEquals(1, f.errors.size)
        assertTrue(f.gains.all { it > 0f })
    }

    private fun fixture(block: suspend (Fixture) -> Unit) = runBlocking {
        val f = Fixture()
        try { withTimeout(5_000) { block(f) } }
        finally {
            f.controller.close().join()
            f.worker.close()
            f.volume.close()
        }
    }

    private class Fixture {
        val worker = Executors.newSingleThreadExecutor { Thread(it, "eq-worker") }.asCoroutineDispatcher()
        val volume = Executors.newSingleThreadExecutor { Thread(it, "eq-volume") }.asCoroutineDispatcher()
        val effects = mutableListOf<FakeEffect>()
        val gains = mutableListOf(1f)
        val volumeThreads = mutableListOf<String>()
        val errors = mutableListOf<Throwable>()
        var onCreate: () -> Unit = {}
        var frequencies = com.cleartune.core.datastore.EQUALIZER_FREQUENCIES_HZ
        val controller = EqualizerController(
            createEffect = { session -> onCreate(); FakeEffect(session, frequencies) { gains.last() }.also(effects::add) },
            onHeadroom = { gains.add(it); volumeThreads.add(Thread.currentThread().name) },
            onFailure = errors::add,
            dispatcher = worker,
            volumeDispatcher = volume,
        )
    }

    private class FakeEffect(
        val session: Int,
        override val centerFrequenciesHz: List<Int>,
        private val gain: () -> Float,
    ) : EqualizerEffect {
        override var enabled = false
            get() { checkUsable(); return field }
            set(value) {
                checkUsable()
                if (value) {
                    levelsAtEnable = levels.toList()
                    gainAtEnable = gain()
                }
                field = value
            }
        override val levelRange = -1500..1500
        val levels = IntArray(centerFrequenciesHz.size) { 300 } // Device preset must be cleared before enabling.
        val threads = mutableListOf<String>()
        var releases = 0
        var failWrites = false
        var levelsAtRelease = emptyList<Int>()
        var levelsAtEnable = emptyList<Int>()
        var gainAtEnable = 1f
        override fun hasControl() = true.also { checkUsable() }
        override fun getLevel(band: Int) = levels[band].toShort().also { checkUsable() }
        override fun setLevel(band: Int, level: Short) {
            checkUsable()
            check(!failWrites) { "Simulated native failure" }
            levels[band] = level.toInt()
        }
        override fun release() {
            checkUsable()
            levelsAtRelease = levels.toList()
            releases++
        }
        private fun checkUsable() {
            threads.add(Thread.currentThread().name)
            check(releases == 0) { "Native handle used after release" }
        }
    }
}
