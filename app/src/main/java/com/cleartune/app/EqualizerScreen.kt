package com.cleartune.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleartune.app.settings.SettingsViewModel
import com.cleartune.core.datastore.EQUALIZER_FREQUENCIES_HZ
import com.cleartune.core.datastore.EqualizerPreset
import kotlin.math.roundToInt

private data class PresetPresentation(
    val preset: EqualizerPreset,
    val title: Int,
    val description: Int,
    val icon: ImageVector,
)

private val presetPresentations = listOf(
    PresetPresentation(EqualizerPreset.BALANCED, R.string.eq_balanced, R.string.eq_balanced_description, Icons.Rounded.GraphicEq),
    PresetPresentation(EqualizerPreset.CLEAR_VOCAL, R.string.eq_clear_vocal, R.string.eq_clear_vocal_description, Icons.Rounded.RecordVoiceOver),
    PresetPresentation(EqualizerPreset.WARM_BASS, R.string.eq_warm_bass, R.string.eq_warm_bass_description, Icons.Rounded.Speaker),
    PresetPresentation(EqualizerPreset.AIRY_TREBLE, R.string.eq_airy_treble, R.string.eq_airy_treble_description, Icons.Rounded.AutoAwesome),
    PresetPresentation(EqualizerPreset.NIGHT_SOFT, R.string.eq_night_soft, R.string.eq_night_soft_description, Icons.Rounded.Bedtime),
    PresetPresentation(EqualizerPreset.CUSTOM, R.string.eq_custom, R.string.eq_custom_description, Icons.Rounded.Tune),
)

@Composable
internal fun EqualizerScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val equalizer = settings.equalizer
    var customLevels by remember { mutableStateOf(equalizer.customLevelsDb) }
    LaunchedEffect(equalizer.customLevelsDb) { customLevels = equalizer.customLevelsDb }
    val displayedLevels = if (equalizer.preset == EqualizerPreset.CUSTOM) {
        customLevels
    } else {
        equalizer.activeLevelsDb
    }

    Scaffold(
        topBar = {
            ClearTuneTopAppBar(
                title = stringResource(R.string.equalizer),
                onBack = onBack,
                actions = {
                    Switch(
                        checked = equalizer.enabled,
                        onCheckedChange = viewModel::setEqualizerEnabled,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                ClearTuneGradientHeader {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ClearTuneIconTile(Icons.Rounded.GraphicEq)
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp),
                        ) {
                            Text(
                                text = stringResource(
                                    if (equalizer.enabled) R.string.eq_is_on else R.string.eq_is_off,
                                ),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = stringResource(equalizer.preset.presentation().title),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    FrequencyCurve(
                        levelsDb = displayedLevels,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(116.dp)
                            .padding(top = 8.dp),
                    )
                }
            }

            item {
                Column(
                    modifier = Modifier.alpha(if (equalizer.enabled) 1f else 0.56f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        stringResource(R.string.eq_choose_effect),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.eq_choose_effect_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    presetPresentations.chunked(2).forEach { pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            pair.forEach { item ->
                                PresetCard(
                                    presentation = item,
                                    selected = equalizer.preset == item.preset,
                                    enabled = equalizer.enabled,
                                    onClick = { viewModel.setEqualizerPreset(item.preset) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }

            if (equalizer.preset == EqualizerPreset.CUSTOM) {
                item {
                    ClearTuneSectionCard(
                        title = stringResource(R.string.eq_custom_five_band),
                        subtitle = stringResource(R.string.eq_custom_five_band_description),
                        icon = Icons.Rounded.Tune,
                        modifier = Modifier.alpha(if (equalizer.enabled) 1f else 0.56f),
                    ) {
                        EQUALIZER_FREQUENCIES_HZ.forEachIndexed { index, frequency ->
                            EqualizerBand(
                                frequencyHz = frequency,
                                valueDb = customLevels[index],
                                enabled = equalizer.enabled,
                                onValueChange = { value ->
                                    customLevels = customLevels.toMutableList().apply { set(index, value) }
                                },
                                onValueChangeFinished = {
                                    viewModel.setEqualizerCustomLevels(customLevels)
                                },
                            )
                        }
                    }
                }
            }

            item {
                ClearTuneSectionCard(
                    title = stringResource(R.string.eq_auto_headroom),
                    subtitle = stringResource(R.string.eq_auto_headroom_description),
                    icon = Icons.Rounded.Security,
                ) {
                    Text(
                        text = stringResource(R.string.eq_auto_headroom_active),
                        color = if (equalizer.enabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun PresetCard(
    presentation: PresetPresentation,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                modifier = Modifier.size(38.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(presentation.icon, contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(presentation.title),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(presentation.description),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun EqualizerBand(
    frequencyHz: Int,
    valueDb: Int,
    enabled: Boolean,
    onValueChange: (Int) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatFrequency(frequencyHz), modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
            Text(
                stringResource(R.string.eq_db_value, valueDb),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Slider(
            value = valueDb.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            onValueChangeFinished = onValueChangeFinished,
            valueRange = -6f..6f,
            steps = 11,
            enabled = enabled,
        )
    }
}

@Composable
private fun FrequencyCurve(levelsDb: List<Int>, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    val surfaceColor = MaterialTheme.colorScheme.surface
    Canvas(modifier) {
        val horizontalPadding = 8.dp.toPx()
        val usableWidth = size.width - horizontalPadding * 2
        val centerY = size.height / 2
        for (row in 0..4) {
            val y = size.height * row / 4f
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        }
        val points = levelsDb.mapIndexed { index, level ->
            Offset(
                x = horizontalPadding + usableWidth * index / (levelsDb.size - 1).coerceAtLeast(1),
                y = centerY - (level.coerceIn(-6, 6) / 6f) * centerY * 0.78f,
            )
        }
        if (points.isNotEmpty()) {
            val fill = Path().apply {
                moveTo(points.first().x, centerY)
                points.forEach { lineTo(it.x, it.y) }
                lineTo(points.last().x, centerY)
                close()
            }
            drawPath(fill, fillColor)
            points.zipWithNext().forEach { (start, end) ->
                drawLine(lineColor, start, end, strokeWidth = 3.dp.toPx())
            }
            points.forEach { point ->
                drawCircle(surfaceColor, radius = 5.dp.toPx(), center = point)
                drawCircle(lineColor, radius = 3.dp.toPx(), center = point)
            }
        }
    }
}

private fun EqualizerPreset.presentation(): PresetPresentation =
    presetPresentations.first { it.preset == this }

private fun formatFrequency(frequencyHz: Int): String = when {
    frequencyHz >= 1_000 -> if (frequencyHz % 1_000 == 0) {
        "${frequencyHz / 1_000} kHz"
    } else {
        "${frequencyHz / 1_000.0} kHz"
    }
    else -> "$frequencyHz Hz"
}
