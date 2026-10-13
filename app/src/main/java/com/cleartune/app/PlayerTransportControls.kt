package com.cleartune.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun PlayerTransportControls(
    playing: Boolean,
    buffering: Boolean,
    previousLabel: String,
    toggleLabel: String,
    nextLabel: String,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    canSkip: Boolean = true,
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val container = if (dark) Color(0xFFDED0F4) else Color(0xFF3C304F)
    val content = if (dark) Color(0xFF302540) else Color.White
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious, enabled = canSkip, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Rounded.SkipPrevious, previousLabel, Modifier.size(26.dp))
        }
        FilledIconButton(onClick = onToggle, modifier = Modifier.size(70.dp).semantics { contentDescription = toggleLabel },
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = container, contentColor = content)) {
            if (buffering) CircularProgressIndicator(Modifier.size(32.dp), color = content, strokeWidth = 3.dp)
            else Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, Modifier.size(36.dp))
        }
        IconButton(onClick = onNext, enabled = canSkip, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Rounded.SkipNext, nextLabel, Modifier.size(26.dp))
        }
    }
}
