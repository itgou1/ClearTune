package com.cleartune.app

import android.view.View
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

/** A dedicated view scopes the screen-on request to this page without changing shared window flags. */
@Composable
internal fun LyricsKeepScreenOn(enabled: Boolean) {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    AndroidView(
        factory = { context -> View(context) },
        modifier = Modifier.size(0.dp),
        update = { it.keepScreenOn = enabled && lifecycleState == Lifecycle.State.RESUMED },
        onRelease = { it.keepScreenOn = false },
    )
}
