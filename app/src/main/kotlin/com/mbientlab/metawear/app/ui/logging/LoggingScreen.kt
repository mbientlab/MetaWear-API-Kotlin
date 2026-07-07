package com.mbientlab.metawear.app.ui.logging

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mbientlab.metawear.app.core.SensorKey
import com.mbientlab.metawear.app.core.SensorSelection
import com.mbientlab.metawear.app.data.LogSessionRecord
import com.mbientlab.metawear.app.ui.appViewModel
import com.mbientlab.metawear.app.ui.components.ErrorBanner
import com.mbientlab.metawear.app.ui.components.GlassCard
import com.mbientlab.metawear.app.ui.components.LabeledValue
import com.mbientlab.metawear.app.ui.components.SectionHeader
import com.mbientlab.metawear.app.ui.stream.SensorConfigSection
import com.mbientlab.metawear.app.ui.theme.GlassGood
import com.mbientlab.metawear.app.ui.theme.GlassTextDim
import com.mbientlab.metawear.app.vm.DeviceViewModel
import com.mbientlab.metawear.app.vm.DownloadViewModel
import com.mbientlab.metawear.app.vm.LogSessionViewModel

@Composable
fun LoggingScreen() {
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
    val pending = records.filter {
        it.deviceId == deviceId && it.status == LogSessionRecord.Status.STOPPED
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text("Logging", style = MaterialTheme.typography.headlineSmall) }
        item { ErrorBanner(lastError) { vm.clearError() } }

        when (val p = phase) {
            is LogSessionViewModel.Phase.Running -> {
                item {
                    GlassCard {
                        Text("Recording to flash", style = MaterialTheme.typography.titleSmall, color = GlassGood)
                        Text(
                            formatElapsed(elapsed),
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Text(
                            "Started ${p.startedAt}",
                            style = MaterialTheme.typography.bodySmall,
                            color = GlassTextDim,
                        )
                    }
                }
                item {
                    Button(
                        onClick = { vm.stop() },
                        enabled = !isBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Stop Logging") }
                }
            }

            else -> {
                item {
                    SensorConfigSection(
                        modules = modules,
                        selections = selections,
                        onSelectionsChange = { selections = it },
                        loggingMode = true,   // hides stream-only altitude
                    )
                }
                item {
                    Button(
                        onClick = { vm.start(selections) },
                        enabled = !isBusy && selections.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Start Logging") }
                }
            }
        }

        if (pending.isNotEmpty()) {
            item { SectionHeader("Pending download") }
            items(pending, key = { it.id }) { record ->
                GlassCard {
                    LabeledValue(record.selection.displayLabel, record.status.name.lowercase())
                }
            }
            item {
                when (val dp = downloadPhase) {
                    is DownloadViewModel.Phase.Downloading -> {
                        GlassCard {
                            Text("Downloading…", style = MaterialTheme.typography.titleSmall)
                            LinearProgressIndicator(
                                progress = { dp.progress.toFloat() },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "${dp.entries} entries" + (dp.total?.let { " of $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = GlassTextDim,
                            )
                        }
                    }

                    is DownloadViewModel.Phase.Ready -> {
                        GlassCard {
                            Text(
                                "Saved ${dp.snapshots.size} session(s) to history",
                                color = GlassGood,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            dp.warning?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = GlassTextDim)
                            }
                        }
                    }

                    is DownloadViewModel.Phase.Failed -> {
                        ErrorBanner(dp.message) { downloadVm.reset() }
                    }

                    DownloadViewModel.Phase.Idle -> {
                        Button(
                            onClick = { downloadVm.downloadAll(pending) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Download ${pending.size} session(s)") }
                    }
                }
            }
        }
    }
}

private fun formatElapsed(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
