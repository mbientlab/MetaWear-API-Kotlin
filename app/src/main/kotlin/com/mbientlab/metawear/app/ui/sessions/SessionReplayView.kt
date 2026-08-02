package com.mbientlab.metawear.app.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mbientlab.metawear.app.core.AnyChartSample
import com.mbientlab.metawear.app.core.ReplayTimeline
import com.mbientlab.metawear.app.ui.components.TaredQuaternionCube
import com.mbientlab.metawear.app.ui.theme.GlassTextDim
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
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { isPlaying = !isPlaying }) {
                    Text(if (isPlaying) "Pause" else "Play")
                }
                TextButton(onClick = { speed = if (speed >= 4.0) 1.0 else speed * 2 }) {
                    Text("${speed.toInt()}×")
                }
            }
            Text(
                "${formatTime(position)} / ${formatTime(timeline.duration)}",
                style = MaterialTheme.typography.labelMedium,
                color = GlassTextDim,
            )
        }
    }
}

private fun formatTime(seconds: Double): String {
    val whole = seconds.toInt()
    return "%d:%02d".format(whole / 60, whole % 60)
}
