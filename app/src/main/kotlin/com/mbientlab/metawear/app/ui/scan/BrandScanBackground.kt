package com.mbientlab.metawear.app.ui.scan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.mbientlab.metawear.app.ui.theme.Palette

/**
 * Decorative background for the connection screen: the brand's solid
 * #FE9500 field, broken up with static white line-art so it doesn't read as
 * a wall of orange — an oversized "m" watermark rising from the bottom
 * corner and a set of concentric scan ripples radiating from the
 * bottom-leading edge, a nod to what this screen does all day. No motion.
 *
 * In light mode a 25 % white veil lifts the orange toward cream so the
 * white device cards don't jump too harshly off it; dark cards sit
 * comfortably on the full orange, so no veil there.
 */
@Composable
fun BrandScanBackground(modifier: Modifier = Modifier) {
    val isLight = !isSystemInDarkTheme()
    val textMeasurer = rememberTextMeasurer()
    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        drawRect(Palette.accent)
        if (isLight) drawRect(Color.White.copy(alpha = 0.25f))

        // Scan ripples centred just off the bottom-leading corner.
        for (ring in 1..5) {
            drawCircle(
                color = Color.White.copy(alpha = 0.12f),
                radius = ring * w * 0.45f / 2f,
                center = Offset(0f, h),
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }

        // Oversized "m" peeking up from the bottom-trailing edge — the
        // device lists live in the top half, so the mark fills the quiet
        // space below them.
        val fontSizeSp = (w * 0.95f).toSp()
        val layout = textMeasurer.measure(
            text = "m",
            style = TextStyle(
                fontSize = fontSizeSp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.SansSerif,
                color = Color.White.copy(alpha = 0.14f),
            ),
        )
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(
                x = w * 0.74f - layout.size.width / 2f,
                y = h * 0.92f - layout.size.height / 2f,
            ),
        )
    }
}
