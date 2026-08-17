package com.mbientlab.metawear.app.ui.stream

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mbientlab.metawear.app.core.SensorKey
import com.mbientlab.metawear.app.core.SensorSelection
import com.mbientlab.metawear.app.export.CsvShare
import com.mbientlab.metawear.app.export.ExportFilename
import com.mbientlab.metawear.app.export.LiveBufferCsvExporter
import com.mbientlab.metawear.app.ui.appViewModel
import com.mbientlab.metawear.app.ui.components.AppScaffold
import com.mbientlab.metawear.app.ui.components.BrandCard
import com.mbientlab.metawear.app.ui.components.FusionCalibrationBadge
import com.mbientlab.metawear.app.ui.components.Notice
import com.mbientlab.metawear.app.ui.components.SensorChartCard
import com.mbientlab.metawear.app.ui.components.SessionStatsBar
import com.mbientlab.metawear.app.ui.components.TaredQuaternionCube
import com.mbientlab.metawear.app.ui.components.icon
import com.mbientlab.metawear.app.ui.theme.Palette
import com.mbientlab.metawear.app.vm.Channel
import com.mbientlab.metawear.app.vm.DeviceViewModel
import com.mbientlab.metawear.app.vm.StreamSessionViewModel
import java.util.Locale
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Configure → stream in one screen: the sensor picker with a Start action
 * until streaming begins, then the stats bar, calibration card, and one
 * chart card per channel with Export / Pause / Stop in the top bar.
 */
@Composable
fun LiveStreamScreen(onBack: () -> Unit) {
    val deviceVm = appViewModel(::DeviceViewModel)
    val vm = appViewModel(::StreamSessionViewModel)
    val context = LocalContext.current

    val modules by deviceVm.modules.collectAsState()
    val channels by vm.channels.collectAsState()
    val isStreaming by vm.isStreaming.collectAsState()
    val isPaused by vm.isPaused.collectAsState()
    val isBusy by vm.isBusy.collectAsState()
    val startedAt by vm.startedAt.collectAsState()
    val lastError by vm.lastError.collectAsState()
    val calibration by vm.calibration.collectAsState()

    var selections by remember {
        mutableStateOf(listOf(SensorSelection(SensorKey.ACCELEROMETER)))
    }

    fun exportAll() {
        val timestamp = Clock.System.now()
        val files = channels.map { channel ->
            val csv = LiveBufferCsvExporter.export(channel.captureBuffer(), channel.selection)
            val filename = ExportFilename.make(
                deviceName = deviceVm.displayName,
                sensorTag = channel.selection.key.shortTag,
                timestamp = timestamp,
            )
            filename to csv
        }
        CsvShare.shareAll(context, files)
    }

    AppScaffold(
        title = if (isStreaming) "Live Stream" else "Configure Stream",
        onBack = onBack,
        notice = lastError?.let { Notice(it) { vm.clearError() } },
        actions = {
            if (!isStreaming) {
                Button(
                    onClick = { vm.start(selections) },
                    enabled = !isBusy && selections.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.success, contentColor = Color.White),
                    modifier = Modifier.padding(end = 8.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Start")
                }
            } else {
                IconButton(onClick = ::exportAll, enabled = channels.isNotEmpty()) {
                    Icon(Icons.Filled.Share, contentDescription = "Export CSV")
                }
                IconButton(onClick = { vm.togglePause() }, enabled = !isBusy) {
                    Icon(
                        if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = if (isPaused) "Resume" else "Pause",
                    )
                }
                IconButton(onClick = { vm.stop() }, enabled = !isBusy) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop", tint = Palette.danger)
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!isStreaming) {
                item {
                    SensorConfigSection(
                        modules = modules,
                        selections = selections,
                        onSelectionsChange = { selections = it },
                    )
                }
            } else {
                startedAt?.let { started ->
                    item { StatsBar(started, channels) }
                }
                calibration?.let { item { FusionCalibrationBadge(it) } }
                items(channels, key = { it.id }) { channel ->
                    ChannelCard(channel)
                }
            }
        }
    }
}

/** Stats strip whose sample total is summed live across every channel. */
@Composable
private fun StatsBar(startedAt: Instant, channels: List<Channel>) {
    var total = 0
    channels.forEach { channel ->
        val ui by channel.ui.collectAsState()
        total += ui.totalSamples
    }
    SessionStatsBar(startedAt = startedAt, totalSamples = total)
}

@Composable
private fun ChannelCard(channel: Channel) {
    val ui by channel.ui.collectAsState()
    val key = channel.selection.key
    val style = key.axisStyle

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Live 3D orientation for the quaternion output — tared to a reference
        // pose, with a Zero button to re-zero on demand.
        if (key == SensorKey.FUSION_QUATERNION) {
            BrandCard { TaredQuaternionCube(latest = ui.latest) }
        }

        SensorChartCard(
            title = key.title,
            icon = key.icon,
            samples = ui.displayBuffer,
            latest = ui.latest,
            style = style,
            rateLabel = if (key.isPolled) {
                "every " + SensorSelection.formatPollInterval(channel.selection.effectivePollIntervalMs)
            } else if (ui.effectiveHz > 0) {
                String.format(Locale.US, "%.1f Hz", ui.effectiveHz)
            } else {
                null
            },
            footer = {
                Text(
                    "${ui.totalSamples} samples" +
                        (if (!key.isPolled) " · plotting 1/${channel.displayStride}" else "") +
                        (style.unit.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
    }
}
