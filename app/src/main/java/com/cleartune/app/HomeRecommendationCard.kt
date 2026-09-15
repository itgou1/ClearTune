package com.cleartune.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** A single playback target: the small cover overlay is backed by the whole card's touch area. */
@Composable
internal fun HomeRecommendationCard(
    title: String,
    description: String,
    enabled: Boolean,
    hasRearCover: Boolean,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    artwork: @Composable (index: Int, modifier: Modifier) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressScale = rememberHomePressScale(interactionSource, enabled)
    Surface(
        onClick = onPlay,
        enabled = enabled,
        modifier = modifier.graphicsLayer {
            scaleX = pressScale.value
            scaleY = pressScale.value
        },
        interactionSource = interactionSource,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            BoxWithConstraints(
                Modifier.fillMaxWidth().clearAndSetSemantics { },
            ) {
                val frontSize = (maxWidth * 0.66f).coerceAtMost(120.dp)
                val rearSize = frontSize * 0.92f
                val rearOffset = (maxWidth * 0.38f).coerceAtMost(68.dp)
                Box(Modifier.fillMaxWidth().height(frontSize + 8.dp)) {
                    if (hasRearCover) {
                        artwork(
                            1,
                            Modifier.offset(x = rearOffset, y = 3.dp).size(rearSize)
                                .graphicsLayer { rotationZ = 6f },
                        )
                    }
                    Box(
                        Modifier.offset(y = 8.dp).size(frontSize)
                            .clip(RoundedCornerShape(12.dp)),
                    ) {
                        artwork(0, Modifier.fillMaxSize())
                        // The visible circle is inset 8 dp; no white rim or protruding edge.
                        // Keep it decorative so accessibility exposes one action per card.
                        Box(
                            Modifier.align(Alignment.BottomEnd).padding(8.dp).size(30.dp)
                                .background(Color.Black.copy(alpha = if (enabled) 0.58f else 0.24f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = Color.White.copy(alpha = if (enabled) 1f else 0.38f),
                            )
                        }
                    }
                }
            }
        }
    }
}
