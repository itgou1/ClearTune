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
    private val boosted = EqualizerSettings(true, EqualizerPreset.CUSTOM, listOf(6, 6, 0, -3, -6))

    @Test fun switchingNeverMutesAndReleaseHappensOnlyAfterFlatCurve() = fixture { f ->
        f.controller.update(1, boosted, true).join()
        assertEquals(0.5011872f, f.gains.last(), 0.0001f)
        f.controller.update(1, boosted.copy(enabled = false), true).join()
        assertFalse(f.controller.isAttached)
        assertTrue(f.gains.all { it >= 0.501f })
        assertTrue(f.gains.zipWithNext().all { (a, b) -> kotlin.math.abs(a - b) < 0.07f })
        assertEquals(1f, f.gains.last(), 0f)
        assertEquals(listOf(0, 0, 0, 0, 0), f.effects.single().levelsAtRelease)
        assertEquals(1, f.effects.single().releases)
        assertTrue(f.effects.single().threads.all { it.substringBefore(" @") == "eq-worker" })
        assertTrue(f.volumeThreads.all { it.substringBefore(" @") == "eq-volume" })
        assertTrue(f.errors.isEmpty())
    }

    @Test fun flatPresetDoesNotChangeVolumeAndRepeatedOffDoesNotCreateAnEffect() = fixture { f ->
        f.controller.update(1, EqualizerSettings(enabled = true), true).join()
        repeat(3) { f.controller.update(1, EqualizerSettings(), true).join() }
        assertTrue(f.gains.all { it == 1f })
        assertEquals(1, f.effects.size)
        assertEquals(1, f.effects.single().releases)
    }

    @Test fun rapidReversalKeepsLatestCurveAndDoesNotReleaseReenabledEffect() = fixture { f ->
        f.controller.update(1, boosted, false).join()
        f.controller.update(1, boosted.copy(enabled = false), true)
        delay(25)
        f.controller.update(1, boosted, true).join()
        assertTrue(f.controller.isAttached)
        assertEquals(0, f.effects.single().releases)
        assertEquals(listOf(600, 600, 0, -300, -600), f.effects.single().levels.toList())
        f.controller.update(1, boosted.copy(enabled = false), true).join()
        assertFalse(f.controller.isAttached)
        assertEquals(1f, f.gains.last(), 0f)
        assertTrue(f.errors.isEmpty())
    }

    @Test fun sessionReplacementReleasesOldHandleAndDoesNotRestoreStaleSettings() = fixture { f ->
        f.controller.update(1, boosted, false).join()
        f.controller.update(2, boosted, false).join()
        assertEquals(listOf(1, 2), f.effects.map { it.session })
        assertEquals(1, f.effects.first().releases)
        f.controller.update(2, boosted.copy(enabled = false), true).join()
        assertTrue(f.effects.all { it.releases == 1 })
        assertFalse(f.controller.isAttached)
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
        val controller = EqualizerController(
            createEffect = { session -> onCreate(); FakeEffect(session).also(effects::add) },
            onHeadroom = { gains.add(it); volumeThreads.add(Thread.currentThread().name) },
            onFailure = errors::add,
            dispatcher = worker,
            volumeDispatcher = volume,
        )
    }

    private class FakeEffect(val session: Int) : EqualizerEffect {
        override var enabled = false
            get() { checkUsable(); return field }
            set(value) { checkUsable(); field = value }
        override val centerFrequenciesHz = listOf(60, 230, 910, 3600, 14000)
        override val levelRange = -1500..1500
        val levels = IntArray(5) { 300 } // Device preset must be cleared before enabling.
        val threads = mutableListOf<String>()
        var releases = 0
        var failWrites = false
        var levelsAtRelease = emptyList<Int>()
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
