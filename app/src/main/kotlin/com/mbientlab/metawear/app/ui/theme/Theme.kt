package com.mbientlab.metawear.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Dark-first palette echoing the Swift app's "Glass" look: deep blue-black
// backgrounds, translucent card surfaces, cool cyan accent. (The app is
// dark-themed in both system modes, like the original.)

val GlassBackground = Color(0xFF0B0F14)
val GlassSurface = Color(0xFF141B24)
val GlassSurfaceHigh = Color(0xFF1C2530)
val GlassAccent = Color(0xFF4FC3F7)
val GlassAccentDim = Color(0xFF2A5A70)
val GlassText = Color(0xFFE4EAF1)
val GlassTextDim = Color(0xFF8A97A6)
val GlassWarn = Color(0xFFFFB74D)
val GlassError = Color(0xFFEF6E6E)
val GlassGood = Color(0xFF69D18C)

// Chart channel colors (x/red, y/green, z/blue, w/extra purple) — matches the
// Swift axis styling.
val ChannelColors = listOf(
    Color(0xFFEF6E6E), // red
    Color(0xFF69D18C), // green
    Color(0xFF5D9BF7), // blue
    Color(0xFFB086F2), // purple
)

/** Quaternion/Euler order the Swift app uses: w/heading leads in purple. */
val FourChannelColors = listOf(
    Color(0xFFB086F2), // w / heading
    Color(0xFFEF6E6E), // x / pitch
    Color(0xFF69D18C), // y / roll
    Color(0xFF5D9BF7), // z / yaw
)

private val DarkScheme = darkColorScheme(
    primary = GlassAccent,
    onPrimary = Color(0xFF06222E),
    secondary = GlassAccentDim,
    onSecondary = GlassText,
    background = GlassBackground,
    onBackground = GlassText,
    surface = GlassSurface,
    onSurface = GlassText,
    surfaceVariant = GlassSurfaceHigh,
    onSurfaceVariant = GlassTextDim,
    error = GlassError,
)

@Composable
fun MetaWearTheme(content: @Composable () -> Unit) {
    // Dark in both modes, like the Swift app's glass design.
    isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = DarkScheme,
        content = content,
    )
}
