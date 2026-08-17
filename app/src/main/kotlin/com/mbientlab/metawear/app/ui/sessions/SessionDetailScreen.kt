package com.mbientlab.metawear.app.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mbientlab.metawear.app.core.AnyChartSample
import com.mbientlab.metawear.app.core.ReplayTimeline
import com.mbientlab.metawear.app.core.SessionAxisStyle
import com.mbientlab.metawear.app.export.CsvShare
import com.mbientlab.metawear.app.export.ExportFilename
import com.mbientlab.metawear.app.ui.appViewModel
import com.mbientlab.metawear.app.ui.components.AppScaffold
import com.mbientlab.metawear.app.ui.components.BrandCard
import com.mbientlab.metawear.app.ui.components.Notice
import com.mbientlab.metawear.app.ui.components.SensorChartCard
import com.mbientlab.metawear.app.ui.components.formatTime
import com.mbientlab.metawear.app.ui.components.sensorIconForLabel
import com.mbientlab.metawear.app.ui.theme.Palette
import com.mbientlab.metawear.app.vm.SessionHistoryViewModel
import com.mbientlab.metawear.persistence.SessionSnapshot
import java.text.NumberFormat
import kotlinx.coroutines.launch

/**
 * One saved session: stats card, the 3D replay for quaternion captures, the
 * chart preview (most recent 600 samples), and CSV export.
 */
@Composable
fun SessionDetailScreen(sessionId: String, onBack: () -> Unit) {
    val vm = appViewModel(::SessionHistoryViewModel)
    val sessions by vm.sessions.collectAsState()
    val lastError by vm.lastError.collectAsState()
    val session = sessions.firstOrNull { it.id == sessionId }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var preview by remember { mutableStateOf<List<AnyChartSample>?>(null) }
    var replay by remember { mutableStateOf<Pair<List<AnyChartSample>, ReplayTimeline>?>(null) }

    LaunchedEffect(session?.id) {
        val snapshot = session ?: return@LaunchedEffect
        preview = vm.loadPreview(snapshot)
        if (snapshot.sensorKind == "quaternion" && snapshot.sampleCount >= 2) {
            replay = vm.loadReplay(snapshot)?.let { (samples, ticks) -> samples to ReplayTimeline(ticks) }
        }
    }

    AppScaffold(
        title = session?.let { it.label ?: it.sensorKind.replaceFirstChar { c -> c.uppercase() } } ?: "Session",
        onBack = onBack,
        notice = lastError?.let { Notice(it) { vm.clearError() } },
    ) { padding ->
        if (session == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@AppScaffold
        }
        val style = SessionAxisStyle.forSession(
            sensorKind = session.sensorKind,
            label = session.label,
            channelCount = preview?.firstOrNull()?.channelCount ?: SessionAxisStyle.channelCountFor(session.sensorKind),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StatsCard(session) }
            replay?.let { (samples, timeline) ->
                item { BrandCard { SessionReplayView(samples = samples, timeline = timeline) } }
            }
            preview?.let { samples ->
                if (samples.isNotEmpty()) {
                    item {
                        SensorChartCard(
                            title = session.label?.substringBefore(" · ")
                                ?: session.sensorKind.replaceFirstChar { it.uppercase() },
                            icon = sensorIconForLabel(session.label),
                            samples = samples,
                            latest = samples.last(),
                            style = style,
                        )
                    }
                }
            }
            item {
                Button(
                    onClick = {
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
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Export CSV")
                }
            }
        }
    }
}

@Composable
private fun StatsCard(session: SessionSnapshot) {
    BrandCard(verticalSpacing = 6.dp) {
        StatLine(Icons.Filled.Tag, "${NumberFormat.getIntegerInstance().format(session.sampleCount)} samples")
        StatLine(Icons.Outlined.Schedule, "${session.startDate.formatTime()} – ${session.endDate.formatTime()}")
        StatLine(Icons.Filled.Sensors, session.deviceName?.let { "$it · ${session.deviceModel}" } ?: session.deviceModel)
        StatLine(Icons.Filled.Build, "Firmware ${session.deviceFirmware}")
        session.groupID?.let { StatLine(Icons.Filled.Tag, "Group ${it.take(8)}") }
    }
}

@Composable
private fun StatLine(icon: ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
