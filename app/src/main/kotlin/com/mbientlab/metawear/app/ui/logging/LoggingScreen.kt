package com.mbientlab.metawear.app.ui.logging

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mbientlab.metawear.app.core.SensorKey
import com.mbientlab.metawear.app.core.SensorSelection
import com.mbientlab.metawear.app.data.LogSessionRecord
import com.mbientlab.metawear.app.ui.appViewModel
import com.mbientlab.metawear.app.ui.components.AppScaffold
import com.mbientlab.metawear.app.ui.components.BrandCard
import com.mbientlab.metawear.app.ui.components.GroupCard
import com.mbientlab.metawear.app.ui.components.Notice
import com.mbientlab.metawear.app.ui.components.SectionFooter
import com.mbientlab.metawear.app.ui.components.SectionHeader
import com.mbientlab.metawear.app.ui.components.cardListItemColors
import com.mbientlab.metawear.app.ui.components.formatDuration
import com.mbientlab.metawear.app.ui.components.formatTime
import com.mbientlab.metawear.app.ui.components.icon
import com.mbientlab.metawear.app.ui.components.sensorIconForLabel
import com.mbientlab.metawear.app.ui.stream.SensorConfigSection
import com.mbientlab.metawear.app.ui.theme.Palette
import com.mbientlab.metawear.app.ui.theme.forText
import com.mbientlab.metawear.app.vm.DeviceViewModel
import com.mbientlab.metawear.app.vm.DownloadViewModel
import com.mbientlab.metawear.app.vm.LogSessionViewModel
import com.mbientlab.metawear.persistence.SessionSnapshot

/**
 * On-board logging: sensor picker (locked while recording), a phase status
 * row, the active loggers, and the download flow for stopped sessions.
 * The primary action (Start / Stop / Download) lives in the top bar.
 */
@Composable
fun LoggingScreen(onBack: () -> Unit) {
    val deviceVm = appViewModel(::DeviceViewModel)
    val vm = appViewModel(::LogSessionViewModel)
    val downloadVm = appViewModel(::DownloadViewModel)

    val modules by deviceVm.modules.collectAsState()
    val phase by vm.phase.collectAsState()
    val elapsed by vm.elapsedSeconds.collectAsState()
    val records by vm.records.collectAsState()
    val isBusy by vm.isBusy.collectAsState()
    val lastError by vm.lastError.collectAsState()
    val downloadPhase by downloadVm.phase.collectAsState()

    var selections by remember {
        mutableStateOf(listOf(SensorSelection(SensorKey.ACCELEROMETER)))
    }

    val deviceId = deviceVm.device?.identifier
    val running = records.filter { it.deviceId == deviceId && it.status == LogSessionRecord.Status.RUNNING }
    val pending = records.filter { it.deviceId == deviceId && it.status == LogSessionRecord.Status.STOPPED }
    val isRunning = phase is LogSessionViewModel.Phase.Running
    val notice = lastError?.let { Notice(it) { vm.clearError() } }
        ?: (downloadPhase as? DownloadViewModel.Phase.Failed)?.let { Notice(it.message) { downloadVm.reset() } }

    AppScaffold(
        title = "Logging",
        onBack = onBack,
        notice = notice,
        actions = {
            when {
                isRunning -> OutlinedButton(
                    onClick = { vm.stop() },
                    enabled = !isBusy,
                    modifier = Modifier.padding(end = 8.dp),
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Stop")
                }
                phase is LogSessionViewModel.Phase.BoardLogging -> OutlinedButton(
                    onClick = { vm.stopBoardLogging() },
                    enabled = !isBusy,
                    modifier = Modifier.padding(end = 8.dp),
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Stop board")
                }
                pending.isNotEmpty() && downloadPhase is DownloadViewModel.Phase.Idle -> Button(
                    onClick = { downloadVm.downloadAll(pending) },
                    modifier = Modifier.padding(end = 8.dp),
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Download")
                }
                else -> Button(
                    onClick = { vm.start(selections) },
                    enabled = !isBusy && selections.isNotEmpty() && phase !is LogSessionViewModel.Phase.Running,
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.danger, contentColor = Color.White),
                    modifier = Modifier.padding(end = 8.dp),
                ) {
                    Icon(Icons.Filled.FiberManualRecord, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Start")
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                // While recording, mirror the running loggers into the locked
                // picker so the user sees what is actually being captured.
                SensorConfigSection(
                    modules = modules,
                    selections = if (isRunning && running.isNotEmpty()) running.map { it.selection } else selections,
                    onSelectionsChange = { selections = it },
                    loggingMode = true, // hides stream-only altitude
                    locked = isRunning,
                )
            }

            item {
                val (icon, tint, text) = when (val p = phase) {
                    LogSessionViewModel.Phase.Idle -> Triple(
                        Icons.Outlined.Circle,
                        MaterialTheme.colorScheme.onSurfaceVariant,
                        "Idle · tap Start to begin",
                    )
                    is LogSessionViewModel.Phase.Running -> Triple(
                        Icons.Filled.FiberManualRecord,
                        Palette.danger,
                        "Logging · ${formatDuration(elapsed)} · started ${p.startedAt.formatTime()}",
                    )
                    LogSessionViewModel.Phase.Stopped -> Triple(
                        Icons.Filled.CheckCircle,
                        Palette.success,
                        "Stopped · ${formatDuration(elapsed)} captured",
                    )
                    is LogSessionViewModel.Phase.BoardLogging -> Triple(
                        Icons.Filled.FiberManualRecord,
                        Palette.warning,
                        "Board is logging on its own · ${p.loggerCount} logger" +
                            (if (p.loggerCount == 1) "" else "s") + " · ${"%,d".format(p.entryCount)} entries",
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BrandCard {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(icon, contentDescription = null, tint = tint)
                            Text(text, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
                        }
                    }
                    SectionFooter(
                        if (phase is LogSessionViewModel.Phase.BoardLogging) {
                            "This app didn't start this session — it was armed by an earlier run, another app, " +
                                "or a session whose record was lost. Stop board keeps the recorded entries; " +
                                "recover them from Settings, or clear them there."
                        } else {
                            "The board keeps logging even if you close the app."
                        },
                    )
                }
            }

            if (isRunning && running.isNotEmpty()) {
                item { SectionHeader("Active Loggers") }
                item {
                    GroupCard {
                        running.forEachIndexed { index, record ->
                            if (index > 0) HorizontalDivider()
                            ListItem(
                                headlineContent = { Text(record.selection.displayLabel) },
                                supportingContent = { Text(record.startDate.formatTime()) },
                                leadingContent = {
                                    Icon(record.selection.key.icon, contentDescription = null, tint = Palette.accent)
                                },
                                colors = cardListItemColors(),
                            )
                        }
                    }
                }
            }

            if (pending.isNotEmpty() || downloadPhase !is DownloadViewModel.Phase.Idle) {
                item { SectionHeader("Download") }
                if (pending.isNotEmpty()) {
                    item {
                        GroupCard {
                            pending.forEachIndexed { index, record ->
                                if (index > 0) HorizontalDivider()
                                ListItem(
                                    headlineContent = { Text(record.selection.displayLabel) },
                                    supportingContent = { Text("Stopped — awaiting download") },
                                    leadingContent = {
                                        Icon(record.selection.key.icon, contentDescription = null, tint = Palette.accent)
                                    },
                                    colors = cardListItemColors(),
                                )
                            }
                        }
                    }
                }
                item { DownloadStateCard(downloadPhase, onDismiss = { downloadVm.reset() }) }
            }
        }
    }
}

/** Progress / complete / failed card for the flash readout. */
@Composable
private fun DownloadStateCard(phase: DownloadViewModel.Phase, onDismiss: () -> Unit) {
    when (phase) {
        DownloadViewModel.Phase.Idle -> Unit
        is DownloadViewModel.Phase.Downloading -> BrandCard(verticalSpacing = 12.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Download, contentDescription = null, tint = Palette.accent)
                Column {
                    Text("Downloading", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (phase.total != null && phase.total > 0) "${phase.entries} / ${phase.total} entries"
                        else "Reading log length…",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            LinearProgressIndicator(
                progress = { phase.progress.toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "${(phase.progress * 100).toInt()}%",
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(Alignment.End),
            )
        }
        is DownloadViewModel.Phase.Ready -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 4.dp),
            ) {
                Icon(Icons.Filled.Verified, contentDescription = null, tint = Palette.success)
                Text("Download complete", style = MaterialTheme.typography.titleSmall)
            }
            phase.warning?.let { warning ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 4.dp),
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = Palette.warning, modifier = Modifier.size(16.dp))
                    Text(warning, style = MaterialTheme.typography.bodySmall, color = Palette.warning.forText())
                }
            }
            GroupCard {
                phase.snapshots.forEachIndexed { index, snapshot ->
                    if (index > 0) HorizontalDivider()
                    SessionDownloadRow(snapshot)
                }
            }
            SectionFooter("Saved to Session History — open it from the device hub to chart, replay, or export.")
        }
        is DownloadViewModel.Phase.Failed -> BrandCard {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = Palette.danger)
                Text("Download failed", style = MaterialTheme.typography.titleSmall)
            }
            Text(phase.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

/** One downloaded-session row: sensor icon, label, sample count and start time. */
@Composable
private fun SessionDownloadRow(snapshot: SessionSnapshot) {
    val icon: ImageVector = sensorIconForLabel(snapshot.label)
    ListItem(
        headlineContent = { Text(snapshot.label ?: snapshot.sensorKind.replaceFirstChar { it.uppercase() }) },
        supportingContent = {
            Text("${snapshot.sampleCount} samples · ${snapshot.startDate.formatTime()}", fontFamily = FontFamily.Monospace)
        },
        leadingContent = { Icon(icon, contentDescription = null, tint = Palette.accent) },
        colors = cardListItemColors(),
    )
}
