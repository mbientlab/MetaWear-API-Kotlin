package com.mbientlab.metawear.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mbientlab.metawear.app.core.AnyChartSample
import com.mbientlab.metawear.app.core.AxisStyle
import com.mbientlab.metawear.app.ui.theme.ChannelColors
import com.mbientlab.metawear.app.ui.theme.FourChannelColors
import com.mbientlab.metawear.app.ui.theme.Palette
import java.text.NumberFormat
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/** Chart line colors for a given axis style (four-channel outputs lead with purple). */
fun AxisStyle.chartColors(): List<Color> = if (chartChannels == 4) FourChannelColors else ChannelColors

/**
 * Compact stats strip above the chart cards: elapsed session time (ticking
 * once a second) and the running total sample count across all channels.
 */
@Composable
fun SessionStatsBar(startedAt: Instant, totalSamples: Int) {
    var elapsedSeconds by remember(startedAt) { mutableIntStateOf(0) }
    LaunchedEffect(startedAt) {
        while (true) {
            elapsedSeconds = ((Clock.System.now() - startedAt).inWholeSeconds).toInt()
            delay(1_000)
        }
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Stat(Icons.Outlined.Schedule, formatDuration(elapsedSeconds))
            VerticalDivider(modifier = Modifier.height(14.dp))
            Stat(Icons.Filled.Tag, "${NumberFormat.getIntegerInstance().format(totalSamples)} samples")
        }
    }
}

@Composable
private fun Stat(icon: ImageVector, label: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
    }
}

/**
 * Row of small chips, one per channel, showing the latest value in the same
 * color as the chart line — precise numbers without eyeballing the trace.
 */
@Composable
fun SensorReadoutChips(style: AxisStyle, latest: AnyChartSample?, colors: List<Color> = style.chartColors()) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        style.labels.take(style.chartChannels).forEachIndexed { index, label ->
            val color = colors.getOrElse(index) { colors.last() }
            val value = latest?.channel(index)
            Surface(shape = CircleShape, color = color.copy(alpha = 0.12f)) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(6.dp).background(color, CircleShape))
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        value?.let { formatMeasurement(it, style.unit) } ?: "—",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (value != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

/**
 * One sensor's card: icon + title with the effective rate on the right, the
 * per-channel readout chips, then the line chart (or a "waiting" placeholder
 * until the first samples land).
 */
@Composable
fun SensorChartCard(
    title: String,
    icon: ImageVector,
    samples: List<AnyChartSample>,
    latest: AnyChartSample?,
    style: AxisStyle,
    rateLabel: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    BrandCard(verticalSpacing = 8.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(20.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (rateLabel != null) {
                Text(
                    rateLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            trailing?.invoke()
        }
        SensorReadoutChips(style = style, latest = latest)
        if (samples.size < 2) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Text(
                    "Waiting for first sample…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LineChart(
                samples = samples,
                channelCount = style.chartChannels,
                colors = style.chartColors(),
                yRange = style.yRange,
                modifier = Modifier.padding(top = 4.dp),
                height = 180.dp,
            )
        }
        footer?.invoke()
    }
}
