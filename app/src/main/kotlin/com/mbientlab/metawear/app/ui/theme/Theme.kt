package com.mbientlab.metawear.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

// Brand-first Material 3 theme: the MetaWear orange is the primary color in
// BOTH light and dark schemes (dynamic color is deliberately not applied so
// the brand always wins), the semantic colors come from [Palette], and the
// surfaces are warm-tinted neutrals so cards and lists sit comfortably next
// to the orange without competing with it.

// Chart channel colors (x/red, y/green, z/blue, w/extra purple). These are
// DATA colors, not brand — kept distinct from the palette on purpose.
val ChannelColors = listOf(
    Color(0xFFEF6E6E), // red
    Color(0xFF69D18C), // green
    Color(0xFF5D9BF7), // blue
    Color(0xFFB086F2), // purple
)

/** Quaternion/Euler chart order: w/heading leads in purple. */
val FourChannelColors = listOf(
    Color(0xFFB086F2), // w / heading
    Color(0xFFEF6E6E), // x / pitch
    Color(0xFF69D18C), // y / roll
    Color(0xFF5D9BF7), // z / yaw
)

private val LightScheme = lightColorScheme(
    primary = Palette.accent,
    // Dark brown on the orange (the launcher icon's "m" on its field) — far
    // more legible than white at button sizes and just as on-brand.
    onPrimary = Color(0xFF3D2200),
    primaryContainer = Color(0xFFFFDDB8),
    onPrimaryContainer = Color(0xFF4A2A00),
    inversePrimary = Color(0xFFFFB866),
    secondary = Color(0xFF7A5A3A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE6CC),
    onSecondaryContainer = Color(0xFF3F2A12),
    tertiary = Palette.info,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD9E6F5),
    onTertiaryContainer = Color(0xFF1B3550),
    error = Palette.danger,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD9),
    onErrorContainer = Color(0xFF5C1A21),
    background = Color(0xFFFFF8F1),
    onBackground = Color(0xFF211A14),
    surface = Color(0xFFFFF8F1),
    onSurface = Color(0xFF211A14),
    surfaceVariant = Color(0xFFF3E6D8),
    onSurfaceVariant = Color(0xFF52463C),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFDF2E8),
    surfaceContainer = Color(0xFFF9EDE0),
    surfaceContainerHigh = Color(0xFFF3E7DA),
    surfaceContainerHighest = Color(0xFFEDE1D4),
    outline = Color(0xFF85766A),
    outlineVariant = Color(0xFFD8C9BB),
    inverseSurface = Color(0xFF362F29),
    inverseOnSurface = Color(0xFFFBEFE5),
    scrim = Color.Black,
)

private val DarkScheme = darkColorScheme(
    primary = Palette.accent,
    onPrimary = Color(0xFF3D2200),
    primaryContainer = Color(0xFF6A3E00),
    onPrimaryContainer = Color(0xFFFFDDB8),
    inversePrimary = Color(0xFF9A5A00),
    secondary = Color(0xFFE6C3A0),
    onSecondary = Color(0xFF432D14),
    secondaryContainer = Color(0xFF5C4328),
    onSecondaryContainer = Color(0xFFFFE6CC),
    tertiary = Palette.info,
    onTertiary = Color(0xFF0E2438),
    tertiaryContainer = Color(0xFF2E4A66),
    onTertiaryContainer = Color(0xFFD9E6F5),
    error = Palette.danger,
    onError = Color(0xFF3F0A12),
    errorContainer = Color(0xFF7A2E36),
    onErrorContainer = Color(0xFFFFDAD9),
    background = Color(0xFF15110D),
    onBackground = Color(0xFFEDE1D6),
    surface = Color(0xFF15110D),
    onSurface = Color(0xFFEDE1D6),
    surfaceVariant = Color(0xFF3B322A),
    onSurfaceVariant = Color(0xFFCDBEB0),
    surfaceContainerLowest = Color(0xFF0F0C09),
    surfaceContainerLow = Color(0xFF1C1712),
    surfaceContainer = Color(0xFF221C16),
    surfaceContainerHigh = Color(0xFF2C251E),
    surfaceContainerHighest = Color(0xFF362E26),
    outline = Color(0xFF968678),
    outlineVariant = Color(0xFF4C4138),
    inverseSurface = Color(0xFFEDE1D6),
    inverseOnSurface = Color(0xFF362F29),
    scrim = Color.Black,
)

/** Default Material type scale with semibold headline/title weights. */
private val AppTypography: Typography = Typography().let { base ->
    base.copy(
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun MetaWearTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = AppTypography,
        content = content,
    )
}
