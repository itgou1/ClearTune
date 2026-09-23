package com.cleartune.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

/** Playback time, rather than wall time, drives both the pauses and the scrolling position. */
internal fun titleScrollPosition(elapsedMs: Double, distance: Float, pixelsPerSecond: Float): Float {
    if (distance <= 0f || pixelsPerSecond <= 0f) return 0f
    val travelMs = distance / pixelsPerSecond * 1_000.0
    val cycleMs = 2_000.0 + travelMs + 2_000.0
    val phaseMs = elapsedMs.coerceAtLeast(0.0) % cycleMs
    return ((phaseMs - 2_000.0).coerceIn(0.0, travelMs) / travelMs * distance).toFloat()
}

@Composable
internal fun PlaybackTitle(
    text: String,
    songId: String,
    playing: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    fontWeight: FontWeight? = null,
) {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active by rememberUpdatedState(playing && lifecycleState == Lifecycle.State.RESUMED)
    var viewportWidth by remember { mutableIntStateOf(0) }
    var textWidth by remember(text, style, fontWeight) { mutableIntStateOf(0) }
    val distance = (textWidth - viewportWidth).coerceAtLeast(0).toFloat()
    val speed = with(LocalDensity.current) { 24.dp.toPx() }
    var offset by remember(songId, text) { mutableFloatStateOf(0f) }
    LaunchedEffect(songId, text, distance, speed) {
        offset = 0f
        if (distance <= 0f) return@LaunchedEffect
        val motionScale = currentCoroutineContext()[MotionDurationScale]
        var elapsedMs = 0.0
        var previousFrame: Long? = null
        while (true) {
            if (!active || motionScale?.scaleFactor == 0f) {
                previousFrame = null
                snapshotFlow { active && motionScale?.scaleFactor != 0f }.first { it }
            }
            withFrameNanos { frame ->
                if (active && motionScale?.scaleFactor != 0f) {
                    previousFrame?.let { previous ->
                        elapsedMs += (frame - previous) / 1_000_000.0
                        offset = titleScrollPosition(elapsedMs, distance, speed)
                    }
                    previousFrame = frame
                } else {
                    previousFrame = null
                }
            }
        }
    }
    Box(modifier.fillMaxWidth().clipToBounds().onSizeChanged { viewportWidth = it.width }) {
        Text(
            text = text,
            style = style,
            fontWeight = fontWeight,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = Modifier
                .offset { IntOffset(-offset.roundToInt(), 0) }
                .wrapContentWidth(Alignment.Start, unbounded = true),
            onTextLayout = { textWidth = it.size.width },
        )
    }
}
