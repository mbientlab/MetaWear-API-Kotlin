package com.mbientlab.metawear.app.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mbientlab.metawear.app.core.AnyChartSample
import com.mbientlab.metawear.app.core.ReplayTimeline
import com.mbientlab.metawear.app.ui.components.TaredQuaternionCube
import kotlinx.coroutines.delay

/**
 * 3D replay of a recorded quaternion session: the tared cube driven by a
 * scrub/play timeline. Playback advances by measured wall time × speed
 * (1× → 2× → 4×) at ~30 Hz; scrubbing pauses and restores the play state.
 */
@Composable
fun SessionReplayView(
    samples: List<AnyChartSample>,
    timeline: ReplayTimeline,
) {
    var position by remember { mutableDoubleStateOf(0.0) }
    var isPlaying by remember { mutableStateOf(false) }
    var isScrubbing by remember { mutableStateOf(false) }
    var wasPlayingBeforeScrub by remember { mutableStateOf(false) }
    var speed by remember { mutableDoubleStateOf(1.0) }

    val current = timeline.index(position)?.let { samples.getOrNull(it) }

    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        if (position >= timeline.duration) position = 0.0
        var lastNanos = System.nanoTime()
        while (isPlaying) {
            delay(33)
            val now = System.nanoTime()
            val elapsed = (now - lastNanos) / 1e9
            lastNanos = now
            position = (position + elapsed * speed).coerceAtMost(timeline.duration)
            if (position >= timeline.duration) isPlaying = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TaredQuaternionCube(latest = current)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledIconButton(onClick = { isPlaying = !isPlaying }) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                )
            }
            Slider(
                value = position.toFloat(),
                onValueChange = { value ->
                    if (!isScrubbing) {
                        isScrubbing = true
                        wasPlayingBeforeScrub = isPlaying
                        isPlaying = false
                    }
                    position = value.toDouble()
                },
                onValueChangeFinished = {
                    isScrubbing = false
                    if (wasPlayingBeforeScrub) isPlaying = true
                },
                valueRange = 0f..maxOf(timeline.duration, 0.001).toFloat(),
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(onClick = { speed = if (speed >= 4.0) 1.0 else speed * 2 }) {
                Text("${speed.toInt()}×", fontFamily = FontFamily.Monospace)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                formatTime(position),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                formatTime(timeline.duration),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatTime(seconds: Double): String {
    val whole = seconds.toInt()
    return "%d:%02d".format(whole / 60, whole % 60)
}
