package com.cleartune.core.designsystem

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val ClearTuneLightColors = lightColorScheme(
    primary = Color(0xFF405991),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E3FF),
    onPrimaryContainer = Color(0xFF102047),
    secondary = Color(0xFF665A83),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9DEFF),
    onSecondaryContainer = Color(0xFF241A3B),
    tertiary = Color(0xFF006782),
    tertiaryContainer = Color(0xFFB9EAFF),
    background = Color(0xFFFAF8FF),
    surface = Color(0xFFFAF8FF),
    surfaceVariant = Color(0xFFE3E7F2),
    outlineVariant = Color(0xFFC4C8D4),
)

private val ClearTuneDarkColors = darkColorScheme(
    primary = Color(0xFFB2C5FF),
    onPrimary = Color(0xFF0C2A60),
    primaryContainer = Color(0xFF263D70),
    onPrimaryContainer = Color(0xFFD9E3FF),
    secondary = Color(0xFFD0C1ED),
    onSecondary = Color(0xFF362E4D),
    secondaryContainer = Color(0xFF4D4465),
    onSecondaryContainer = Color(0xFFEDDDFF),
    tertiary = Color(0xFF88D1EC),
    tertiaryContainer = Color(0xFF004D63),
    background = Color(0xFF11131A),
    surface = Color(0xFF11131A),
    surfaceVariant = Color(0xFF424652),
    outlineVariant = Color(0xFF424652),
)

private val ClearTuneShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(28.dp),
)

@Composable
fun ClearTuneTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val colors = if (darkTheme) ClearTuneDarkColors else ClearTuneLightColors

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colors,
        typography = MaterialTheme.typography,
        shapes = ClearTuneShapes,
        content = content,
    )
}
