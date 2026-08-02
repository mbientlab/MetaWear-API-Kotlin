package com.mbientlab.metawear.app.ui.stream

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mbientlab.metawear.app.core.SensorKey
import com.mbientlab.metawear.app.core.SensorSelection
import com.mbientlab.metawear.app.export.CsvShare
import com.mbientlab.metawear.app.export.ExportFilename
import com.mbientlab.metawear.app.export.LiveBufferCsvExporter
import com.mbientlab.metawear.app.ui.appViewModel
import com.mbientlab.metawear.app.ui.components.ErrorBanner
import com.mbientlab.metawear.app.ui.components.FusionCalibrationBadge
import com.mbientlab.metawear.app.ui.components.GlassCard
import com.mbientlab.metawear.app.ui.components.LineChart
import com.mbientlab.metawear.app.ui.components.TaredQuaternionCube
import com.mbientlab.metawear.app.ui.theme.ChannelColors
import com.mbientlab.metawear.app.ui.theme.FourChannelColors
import com.mbientlab.metawear.app.ui.theme.GlassTextDim
import com.mbientlab.metawear.app.vm.Channel
import com.mbientlab.metawear.app.vm.DeviceViewModel
import com.mbientlab.metawear.app.vm.StreamSessionViewModel
import java.util.Locale
import kotlinx.datetime.Clock

@Composable
fun LiveStreamScreen() {
    val deviceVm = appViewModel(::DeviceViewModel)
    val vm = appViewModel(::StreamSessionViewModel)

    val modules by deviceVm.modules.collectAsState()
    val channels by vm.channels.collectAsState()
    val isStreaming by vm.isStreaming.collectAsState()
    val isPaused by vm.isPaused.collectAsState()
    val isBusy by vm.isBusy.collectAsState()
    val lastError by vm.lastError.collectAsState()

    val calibration by vm.calibration.collectAsState()

    var selections by remember {
        mutableStateOf(listOf(SensorSelection(SensorKey.ACCELEROMETER)))
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text("Live Stream", style = MaterialTheme.typography.headlineSmall) }
        item { ErrorBanner(lastError) { vm.clearError() } }

        if (!isStreaming) {
            item {
                SensorConfigSection(
                    modules = modules,
                    selections = selections,
                    onSelectionsChange = { selections = it },
                )
            }
            item {
                Button(
                    onClick = { vm.start(selections) },
                    enabled = !isBusy && selections.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Start Streaming") }
            }
        } else {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { vm.togglePause() },
                        enabled = !isBusy,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (isPaused) "Resume" else "Pause") }
                    Button(
                        onClick = { vm.stop() },
                        enabled = !isBusy,
                        modifier = Modifier.weight(1f),
                    ) { Text("Stop & Save") }
                }
            }

            calibration?.let { item { FusionCalibrationBadge(it) } }

            items(channels, key = { it.id }) { channel ->
                ChannelCard(channel)
            }
        }
    }
}

@Composable
private fun ChannelCard(channel: Channel) {
    val ui by channel.ui.collectAsState()
    val style = channel.selection.key.axisStyle
    val colors = if (style.chartChannels == 4) FourChannelColors else ChannelColors
    val context = LocalContext.current

    GlassCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(channel.selection.displayLabel, style = MaterialTheme.typography.titleSmall)
            if (!channel.selection.key.isPolled) {
                Text(
                    String.format(Locale.US, "%.1f Hz", ui.effectiveHz),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        val latest = ui.latest
        if (channel.selection.key.isPolled) {
            // Readout tile: one big current value plus the (trivially reused)
            // chart of recent readings.
            Text(
                latest?.let { String.format(Locale.US, "%.2f %s", it.f0, style.unit) } ?: "waiting…",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (ui.displayBuffer.size >= 2) {
                LineChart(
                    samples = ui.displayBuffer,
                    channelCount = 1,
                    colors = colors,
                    yRange = style.yRange,
                )
            }
            Text(
                "${ui.totalSamples} readings · every " +
                    SensorSelection.formatPollInterval(channel.selection.effectivePollIntervalMs),
                style = MaterialTheme.typography.labelSmall,
                color = GlassTextDim,
            )
        } else {
            // Live 3D orientation cube for the quaternion output — tared to
            // a reference pose, with a Zero button to re-zero on demand.
            if (channel.selection.key == SensorKey.FUSION_QUATERNION) {
                TaredQuaternionCube(latest = latest)
            }
            LineChart(
                samples = ui.displayBuffer,
                channelCount = style.chartChannels,
                colors = colors,
                yRange = style.yRange,
            )

            // Live per-axis readout.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                style.labels.take(style.chartChannels).forEachIndexed { index, label ->
                    Text(
                        "$label ${latest?.let { String.format(Locale.US, "%+.3f", it.channel(index)) } ?: "—"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.getOrElse(index) { GlassTextDim },
                    )
                }
            }
            Text(
                "${ui.totalSamples} samples · plotting 1/${channel.displayStride}" +
                    (style.unit.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = GlassTextDim,
            )
        }
        androidx.compose.material3.TextButton(onClick = {
            val csv = LiveBufferCsvExporter.export(channel.captureBuffer(), channel.selection)
            val filename = ExportFilename.make(
                deviceName = "MetaWear",
                sensorTag = channel.selection.key.shortTag,
                timestamp = Clock.System.now(),
            )
            CsvShare.share(context, filename, csv)
        }) { Text("Export buffer CSV") }
    }
}
