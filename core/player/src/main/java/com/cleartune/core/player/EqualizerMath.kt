package com.cleartune.core.player

import kotlin.math.pow

internal object EqualizerMath {
    fun headroomMultiplier(levelsDb: List<Int>): Float {
        val maximumBoostDb = levelsDb.maxOrNull()?.coerceAtLeast(0) ?: 0
        return 10.0.pow(-maximumBoostDb / 20.0).toFloat()
    }

    fun millibels(levelDb: Int, range: IntRange): Short =
        (levelDb.coerceIn(-6, 6) * 100)
            .coerceIn(range)
            .toShort()
}
