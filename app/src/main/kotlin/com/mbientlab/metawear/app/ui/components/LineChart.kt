package com.mbientlab.metawear.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mbientlab.metawear.app.core.AnyChartSample

/**
 * Self-contained Canvas line chart for the decimated live buffer. One path
 * per channel; y-scale is the fixed [yRange] when the sensor declares one,
 * otherwise auto-fit to the visible window with a small margin.
 */
@Composable
fun LineChart(
    samples: List<AnyChartSample>,
    channelCount: Int,
    colors: List<Color>,
    yRange: ClosedFloatingPointRange<Float>?,
    modifier: Modifier = Modifier,
    height: Dp = 160.dp,
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
    ) {
        // Frame + midline.
        drawRect(gridColor, style = Stroke(width = 1.dp.toPx()))

        if (samples.size < 2) return@Canvas

        // Resolve the y window.
        var minY: Float
        var maxY: Float
        if (yRange != null) {
            minY = yRange.start
            maxY = yRange.endInclusive
        } else {
            minY = Float.MAX_VALUE
            maxY = -Float.MAX_VALUE
            for (s in samples) {
                for (c in 0 until channelCount) {
                    val v = s.channel(c)
                    if (v < minY) minY = v
                    if (v > maxY) maxY = v
                }
            }
            if (minY == maxY) {
                minY -= 1f
                maxY += 1f
            }
            val margin = (maxY - minY) * 0.1f
            minY -= margin
            maxY += margin
        }
        val ySpan = maxY - minY

        // Midline (zero when in window, otherwise center).
        val midValue = if (minY <= 0f && 0f <= maxY) 0f else (minY + maxY) / 2f
        val midY = size.height * (1f - (midValue - minY) / ySpan)
        drawLine(
            gridColor,
            start = Offset(0f, midY),
            end = Offset(size.width, midY),
            strokeWidth = 1.dp.toPx(),
        )

        val stepX = size.width / (samples.size - 1)
        for (c in 0 until channelCount) {
            val color = colors.getOrElse(c) { colors.last() }
            val path = Path()
            samples.forEachIndexed { index, sample ->
                val x = index * stepX
                val norm = ((sample.channel(c) - minY) / ySpan).coerceIn(0f, 1f)
                val y = size.height * (1f - norm)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = color, style = Stroke(width = 2.dp.toPx()))
        }
    }
}
