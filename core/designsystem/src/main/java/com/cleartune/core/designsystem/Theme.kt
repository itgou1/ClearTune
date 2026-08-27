package com.cleartune.core.designsystem

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

private val ClearTuneLightColors = lightColorScheme(
    primary = Color(0xFF4965A1),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE5FF),
    onPrimaryContainer = Color(0xFF12234A),
    secondary = Color(0xFF665A83),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEAE2FF),
    onSecondaryContainer = Color(0xFF241A3B),
    tertiary = Color(0xFF006782),
    tertiaryContainer = Color(0xFFB9EAFF),
    background = Color(0xFFF5F3FF),
    surface = Color(0xFFFFFBFF),
    surfaceVariant = Color(0xFFE5E7F1),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAF8FF),
    surfaceContainer = Color(0xFFF1EFFF),
    surfaceContainerHigh = Color(0xFFEBE9F7),
    surfaceContainerHighest = Color(0xFFE5E3F0),
    outline = Color(0xFF747783),
    outlineVariant = Color(0xFFC7C9D4),
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
    surface = Color(0xFF12131B),
    surfaceVariant = Color(0xFF424652),
    surfaceContainerLowest = Color(0xFF0D0F16),
    surfaceContainerLow = Color(0xFF191B24),
    surfaceContainer = Color(0xFF1E202A),
    surfaceContainerHigh = Color(0xFF292B36),
    surfaceContainerHighest = Color(0xFF343640),
    outline = Color(0xFF9093A0),
    outlineVariant = Color(0xFF424652),
)

private val ClearTuneShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val ClearTuneTypography = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
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
        typography = ClearTuneTypography,
        shapes = ClearTuneShapes,
        content = content,
    )
}
