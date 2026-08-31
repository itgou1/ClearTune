package com.cleartune.core.player

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

internal object EqualizerMath {
    fun headroomMultiplier(levelsDb: Iterable<Number>): Float {
        val maximumBoostDb = levelsDb.maxOfOrNull { it.toDouble() }?.coerceAtLeast(0.0) ?: 0.0
        return 10.0.pow(-maximumBoostDb / 20.0).toFloat()
    }

    fun millibels(levelDb: Float, range: IntRange): Short =
        (levelDb.coerceIn(PRODUCT_MIN_DB, PRODUCT_MAX_DB) * 100)
            .roundToInt()
            .coerceIn(range)
            .toShort()
}

fun interpolatedEqualizerLevelDb(
    anchorFrequenciesHz: List<Int>,
    anchorLevelsDb: List<Int>,
    frequencyHz: Int,
): Float {
    require(anchorFrequenciesHz.isNotEmpty() && anchorFrequenciesHz.size == anchorLevelsDb.size)
    if (frequencyHz <= anchorFrequenciesHz.first()) return anchorLevelsDb.first().toFloat()
    if (frequencyHz >= anchorFrequenciesHz.last()) return anchorLevelsDb.last().toFloat()

    val rightIndex = anchorFrequenciesHz.indexOfFirst { it >= frequencyHz }
    val leftIndex = rightIndex - 1
    val leftFrequency = anchorFrequenciesHz[leftIndex].toDouble()
    val rightFrequency = anchorFrequenciesHz[rightIndex].toDouble()
    val logarithmicPosition = (
        (ln(frequencyHz.toDouble()) - ln(leftFrequency)) /
            (ln(rightFrequency) - ln(leftFrequency))
        ).toFloat()
    val leftLevel = anchorLevelsDb[leftIndex].toFloat()
    return leftLevel + (anchorLevelsDb[rightIndex] - leftLevel) * logarithmicPosition
}

fun sampledEqualizerCurveDb(
    anchorFrequenciesHz: List<Int>,
    anchorLevelsDb: List<Int>,
    pointCount: Int = 64,
): List<Float> {
    require(anchorFrequenciesHz.isNotEmpty() && anchorFrequenciesHz.size == anchorLevelsDb.size)
    val count = pointCount.coerceAtLeast(2)
    val start = ln(anchorFrequenciesHz.first().toDouble())
    val span = ln(anchorFrequenciesHz.last().toDouble()) - start
    return List(count) { index ->
        val frequency = kotlin.math.exp(start + span * index / (count - 1)).roundToInt()
        interpolatedEqualizerLevelDb(anchorFrequenciesHz, anchorLevelsDb, frequency)
    }
}

private const val PRODUCT_MIN_DB = -6f
private const val PRODUCT_MAX_DB = 6f
