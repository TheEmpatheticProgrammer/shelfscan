package com.shelfscan.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Library palette: bottle-green cloth, cream paper, brass accents.
private val Ink = Color(0xFF1F3D2E)
private val Paper = Color(0xFFFBF7F0)
private val Brass = Color(0xFFB7862F)

private val Light = lightColorScheme(
    primary = Ink,
    onPrimary = Paper,
    primaryContainer = Color(0xFFD6E6DA),
    onPrimaryContainer = Color(0xFF0E2318),
    secondary = Brass,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF6E3BC),
    onSecondaryContainer = Color(0xFF3A2A06),
    background = Paper,
    onBackground = Color(0xFF1D1B18),
    surface = Paper,
    onSurface = Color(0xFF1D1B18),
    surfaceVariant = Color(0xFFEDE6DA),
    onSurfaceVariant = Color(0xFF4D4639),
    surfaceContainer = Color(0xFFF3EDE3),
    surfaceContainerHigh = Color(0xFFEDE6DA),
    outline = Color(0xFF7F7667),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA9CDB4),
    onPrimary = Color(0xFF0E2318),
    primaryContainer = Color(0xFF2C5240),
    onPrimaryContainer = Color(0xFFD6E6DA),
    secondary = Color(0xFFE8C47A),
    onSecondary = Color(0xFF3A2A06),
    secondaryContainer = Color(0xFF5A4415),
    onSecondaryContainer = Color(0xFFF6E3BC),
    background = Color(0xFF151513),
    onBackground = Color(0xFFE8E2D8),
    surface = Color(0xFF151513),
    onSurface = Color(0xFFE8E2D8),
    surfaceVariant = Color(0xFF2B2924),
    onSurfaceVariant = Color(0xFFCFC6B5),
    surfaceContainer = Color(0xFF1F1E1B),
    surfaceContainerHigh = Color(0xFF2B2924),
    outline = Color(0xFF989080),
)

private val Base = Typography()
private val Serif = FontFamily.Serif

val ShelfTypography = Typography(
    headlineLarge = Base.headlineLarge.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
    headlineMedium = Base.headlineMedium.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
    headlineSmall = Base.headlineSmall.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 11.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun ShelfScanTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = ShelfTypography, content = content)
}
