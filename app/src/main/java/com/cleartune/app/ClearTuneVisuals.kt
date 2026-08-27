package com.cleartune.app

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun clearTuneGradient(): Brush = Brush.linearGradient(
    listOf(
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.secondaryContainer,
    ),
)

@Composable
internal fun ClearTuneGradientHeader(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 22.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(clearTuneGradient(), MaterialTheme.shapes.large)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ClearTuneTopAppBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        navigationIcon = {
            onBack?.let {
                IconButton(onClick = it) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                    )
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    )
}

@Composable
internal fun ClearTunePageHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            subtitle?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        actions()
    }
}

@Composable
internal fun ClearTuneSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 24.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            subtitle?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (action != null && onAction != null) {
            androidx.compose.material3.TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
internal fun ClearTuneEmptyState(
    title: String,
    description: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ClearTuneIconTile(icon = icon, modifier = Modifier.size(56.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (action != null && onAction != null) {
            Button(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
internal fun ClearTuneIconTile(
    icon: ImageVector,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.size(42.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f),
        contentColor = MaterialTheme.colorScheme.primary,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
internal fun ClearTuneArtworkPlaceholder(
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val centerSurface = MaterialTheme.colorScheme.surface.copy(alpha = 0.42f)

    Box(
        modifier = modifier.background(clearTuneGradient()),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val edge = size.minDimension
            drawCircle(
                color = secondary.copy(alpha = 0.13f),
                radius = edge * 0.48f,
                center = androidx.compose.ui.geometry.Offset(size.width * 0.08f, size.height * 0.10f),
            )
            drawCircle(
                color = primary.copy(alpha = 0.13f),
                radius = edge * 0.46f,
                center = androidx.compose.ui.geometry.Offset(size.width * 0.94f, size.height * 0.92f),
            )

            val recordRadius = edge * 0.31f
            val ringWidth = (edge * 0.008f).coerceAtLeast(1f)
            drawCircle(onContainer.copy(alpha = 0.10f), recordRadius)
            listOf(0.55f, 0.72f, 0.88f).forEach { scale ->
                drawCircle(
                    color = onContainer.copy(alpha = 0.16f),
                    radius = recordRadius * scale,
                    style = Stroke(width = ringWidth),
                )
            }
        }
        Surface(
            modifier = Modifier.fillMaxSize(0.46f),
            shape = CircleShape,
            color = centerSurface,
            contentColor = primary,
            border = BorderStroke(1.dp, onContainer.copy(alpha = 0.12f)),
            tonalElevation = 2.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(0.48f),
                )
            }
        }
    }
}

@Composable
internal fun ClearTuneSectionCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            androidx.compose.foundation.layout.Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ClearTuneIconTile(icon)
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    subtitle?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            content()
        }
    }
}

@Composable
internal fun ClearTuneAppMark(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_launcher_owl),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(OwlCutoutShape),
    )
}

private val OwlCutoutShape = GenericShape { size, _ ->
    val width = size.width
    val height = size.height

    // Headphone arch: the reversed inner oval makes the space under the band transparent.
    addOval(Rect(width * 0.13f, height * 0.12f, width * 0.87f, height * 0.70f))
    addOval(
        Rect(width * 0.24f, height * 0.22f, width * 0.76f, height * 0.58f),
        Path.Direction.Clockwise,
    )

    // Owl body, ear tufts and both headphone cups form one transparent-background mark.
    addOval(Rect(width * 0.18f, height * 0.23f, width * 0.82f, height * 0.90f))
    moveTo(width * 0.20f, height * 0.42f)
    lineTo(width * 0.20f, height * 0.22f)
    lineTo(width * 0.42f, height * 0.38f)
    close()
    moveTo(width * 0.80f, height * 0.42f)
    lineTo(width * 0.80f, height * 0.22f)
    lineTo(width * 0.58f, height * 0.38f)
    close()
    addOval(Rect(width * 0.09f, height * 0.39f, width * 0.27f, height * 0.69f))
    addOval(Rect(width * 0.73f, height * 0.39f, width * 0.91f, height * 0.69f))
}
