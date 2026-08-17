package com.mbientlab.metawear.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * Brand palette. The accent is the MetaWear orange (#FE9500) sampled flat
 * from the original connect screen; the semantic colors are deliberately
 * soft so they read apart from it — the warning gold is pushed toward
 * yellow, the danger red toward coral, and the info blue is dusty rather
 * than saturated.
 */
object Palette {
    /** Brand orange — the primary color in both light and dark schemes. */
    val accent = Color(0xFFFE9500)

    /** Soft teal-green: connected, calibrated, saved. */
    val success = Color(red = 0.30f, green = 0.70f, blue = 0.55f)

    /** Gold: connecting, idle, over-budget, bootloader. */
    val warning = Color(red = 0.93f, green = 0.74f, blue = 0.20f)

    /** Softened coral: disconnected, logging (recording), destructive. */
    val danger = Color(red = 0.90f, green = 0.40f, blue = 0.45f)

    /** Dusty blue: signal strength, streaming, medium calibration. */
    val info = Color(red = 0.40f, green = 0.60f, blue = 0.80f)

    /** Mid gray: offline / unknown. */
    val neutral = Color(red = 0.55f, green = 0.55f, blue = 0.55f)
}

/** True when the active color scheme is the dark one. */
@Composable
fun isDarkScheme(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/**
 * The palette color as it should be used for small TEXT: unchanged on dark
 * surfaces, pulled a third of the way toward black on light ones so gold,
 * teal, and orange labels stay legible on cream. Containers and icons keep
 * the pure tint — only type needs the extra contrast.
 */
@Composable
fun Color.forText(): Color = if (isDarkScheme()) this else lerp(this, Color.Black, 0.35f)
