package com.mbientlab.metawear.app.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mbientlab.metawear.app.core.AnyChartSample
import com.mbientlab.metawear.app.core.ReplayTimeline
import com.mbientlab.metawear.app.core.SessionAxisStyle
import com.mbientlab.metawear.app.export.CsvShare
import com.mbientlab.metawear.app.export.ExportFilename
import com.mbientlab.metawear.app.ui.appViewModel
import com.mbientlab.metawear.app.ui.components.ErrorBanner
import com.mbientlab.metawear.app.ui.components.GlassCard
import com.mbientlab.metawear.app.ui.components.LabeledValue
import com.mbientlab.metawear.app.ui.components.LineChart
import com.mbientlab.metawear.app.ui.components.SectionHeader
import com.mbientlab.metawear.app.ui.theme.ChannelColors
import com.mbientlab.metawear.app.ui.theme.FourChannelColors
import com.mbientlab.metawear.app.ui.theme.GlassError
import com.mbientlab.metawear.app.ui.theme.GlassTextDim
import com.mbientlab.metawear.app.vm.SessionHistoryViewModel
import com.mbientlab.metawear.persistence.SessionSnapshot
import kotlinx.coroutines.launch

/**
 * Session history grouped by board, with swipe-to-delete, chart previews with
 * per-sample-type styling, quaternion 3D replay, and CSV export.
 */
@Composable
fun SessionsScreen() {
    val vm = appViewModel(::SessionHistoryViewModel)
    val sections by vm.sections.collectAsState()
    val lastError by vm.lastError.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text("Sessions", style = MaterialTheme.typography.headlineSmall) }
        item { ErrorBanner(lastError) { vm.clearError() } }

        if (sections.isEmpty()) {
            item {
                GlassCard {
                    Text(
                        "No saved sessions yet. Stream (Stop & Save) or download a log to create one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = GlassTextDim,
                    )
                }
            }
        }

        sections.forEach { section ->
            item(key = "header-${section.id}") { SectionHeader(section.title) }
            items(section.sessions, key = { it.id }) { session ->
                DismissableSessionCard(session, vm)
            }
        }
    }
}

@Composable
private fun DismissableSessionCard(session: SessionSnapshot, vm: SessionHistoryViewModel) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                vm.deleteSession(session.id)
                true
            } else {
                false
            }
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(GlassError.copy(alpha = 0.4f)),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text("Delete", modifier = Modifier.padding(end = 24.dp))
            }
        },
    ) {
        SessionCard(session, vm)
    }
}

@Composable
private fun SessionCard(session: SessionSnapshot, vm: SessionHistoryViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var preview by remember { mutableStateOf<List<AnyChartSample>?>(null) }
    var replay by remember { mutableStateOf<Pair<List<AnyChartSample>, ReplayTimeline>?>(null) }

    val isQuaternion = session.sensorKind == "quaternion"
    val style = SessionAxisStyle.forSession(
        sensorKind = session.sensorKind,
        label = session.label,
        channelCount = SessionAxisStyle.channelCountFor(session.sensorKind),
    )

    GlassCard {
        Text(session.label ?: session.sensorKind, style = MaterialTheme.typography.titleSmall)
        session.deviceName?.let { LabeledValue("Board", it) }
        LabeledValue("Samples", session.sampleCount.toString())
        LabeledValue("Start", session.startDate.toString())
        LabeledValue("Device", "${session.deviceModel} · fw ${session.deviceFirmware}")
        session.groupID?.let { LabeledValue("Group", it.take(8)) }

        replay?.let { (samples, timeline) ->
            SessionReplayView(samples = samples, timeline = timeline)
        }

        preview?.let { samples ->
            LineChart(
                samples = samples,
                channelCount = style.chartChannels,
                colors = if (style.chartChannels == 4) FourChannelColors else ChannelColors,
                yRange = style.yRange,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (isQuaternion && session.sampleCount >= 2 && replay == null) {
                TextButton(onClick = {
                    scope.launch {
                        vm.loadReplay(session)?.let { (samples, ticks) ->
                            replay = samples to ReplayTimeline(ticks)
                        }
                    }
                }) { Text("Replay") }
            }
            if (preview == null) {
                TextButton(onClick = {
                    scope.launch { preview = vm.loadPreview(session) }
                }) { Text("Preview") }
            }
            TextButton(onClick = {
                scope.launch {
                    val csv = vm.exportCsv(session) ?: return@launch
                    val filename = ExportFilename.make(
                        // Capture-time name attributes the file; un-renamed
                        // boards get their serial appended instead.
                        deviceName = session.deviceName ?: "MetaWear-${session.deviceSerial}",
                        sensorTag = session.sensorKind,
                        timestamp = session.startDate,
                        discriminator = session.id.take(4),
                    )
                    CsvShare.share(context, filename, csv)
                }
            }) { Text("Export CSV") }
            TextButton(onClick = { vm.deleteSession(session.id) }) { Text("Delete") }
        }
    }
}
