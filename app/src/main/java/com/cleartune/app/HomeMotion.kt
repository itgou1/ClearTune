package com.cleartune.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first

internal fun homeEntranceFraction(progress: Float, section: Int): Float =
    ((progress * 300f - section.coerceIn(0, 2) * 40f) / 220f).coerceIn(0f, 1f)

/** One clock for the entire page: lazy items appearing later do not replay their entrance. */
@Composable
internal fun rememberHomeEntranceProgress(
    ready: Boolean,
    shouldAnimate: Boolean,
    onStarted: () -> Unit,
): State<Float> {
    val runEntrance = remember { shouldAnimate }
    val progress = remember { Animatable(if (runEntrance) 0f else 1f) }
    val currentReady by rememberUpdatedState(ready)
    val currentOnStarted by rememberUpdatedState(onStarted)
    LaunchedEffect(Unit) {
        if (runEntrance) {
            snapshotFlow { currentReady }.first { it }
            // Consume before animating, so navigating away mid-animation cannot replay it.
            currentOnStarted()
            progress.animateTo(1f, tween(durationMillis = 300, easing = LinearEasing))
        }
    }
    return progress.asState()
}

@Composable
internal fun HomeEntranceSection(
    progress: State<Float>,
    section: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier.graphicsLayer {
        val fraction = FastOutSlowInEasing.transform(homeEntranceFraction(progress.value, section))
        alpha = fraction
        translationY = 10.dp.toPx() * (1f - fraction)
    }) { content() }
}

@Composable
internal fun rememberHomePressScale(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
): State<Float> {
    val pressed by interactionSource.collectIsPressedAsState()
    return animateFloatAsState(
        targetValue = if (enabled && pressed) 0.975f else 1f,
        animationSpec = if (pressed && enabled) tween(90) else spring(dampingRatio = 0.85f, stiffness = 500f),
        label = "homeCardPress",
    )
}
