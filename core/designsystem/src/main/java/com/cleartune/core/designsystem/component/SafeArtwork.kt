package com.cleartune.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

fun safeArtworkModel(reference: String?): String? {
    val value = reference?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val scheme = value.substringBefore(':', "").lowercase()
    return value.takeIf { scheme == "content" || scheme == "android.resource" }
}

@Composable
fun SafeArtwork(reference: String?, fallback: String, modifier: Modifier = Modifier) {
    val model = safeArtworkModel(reference)
    var failed by remember(model) { mutableStateOf(false) }
    Box(
        modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (model != null && !failed) {
            AsyncImage(
                model = model,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onError = { failed = true },
            )
        } else {
            Text(fallback, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.headlineSmall)
        }
    }
}
