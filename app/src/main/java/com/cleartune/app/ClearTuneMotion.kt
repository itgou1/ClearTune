package com.cleartune.app

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween

/** Shared motion tokens so navigation, bottom chrome and in-page changes feel related. */
internal object ClearTuneMotion {
    const val QuickDuration = 160
    const val StandardDuration = 240
    const val EmphasizedDuration = 300

    fun <T> quick(): TweenSpec<T> = tween(
        durationMillis = QuickDuration,
        easing = FastOutSlowInEasing,
    )

    fun <T> standard(): TweenSpec<T> = tween(
        durationMillis = StandardDuration,
        easing = FastOutSlowInEasing,
    )

    fun <T> emphasized(): TweenSpec<T> = tween(
        durationMillis = EmphasizedDuration,
        easing = FastOutSlowInEasing,
    )
}
