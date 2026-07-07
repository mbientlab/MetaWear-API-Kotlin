package com.mbientlab.metawear.app.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mbientlab.metawear.app.export.CsvShare
import com.mbientlab.metawear.app.export.ExportFilename
import com.mbientlab.metawear.app.ui.appViewModel
import com.mbientlab.metawear.app.ui.components.ErrorBanner
import com.mbientlab.metawear.app.ui.components.GlassCard
import com.mbientlab.metawear.app.ui.components.LabeledValue
import com.mbientlab.metawear.app.ui.theme.GlassTextDim
import com.mbientlab.metawear.app.vm.SessionHistoryViewModel
import kotlinx.coroutines.launch

/**
 * Persisted-session history: newest first, with per-session CSV export
 * (shared via the system sheet) and delete. Combines the history list and
 * per-session detail essentials into one screen.
 */
@Composable
fun SessionsScreen() {
    val vm = appViewModel(::SessionHistoryViewModel)
    val sessions by vm.sessions.collectAsState()
    val lastError by vm.lastError.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text("Sessions", style = MaterialTheme.typography.headlineSmall) }
        item { ErrorBanner(lastError) { vm.clearError() } }

        if (sessions.isEmpty()) {
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

        items(sessions, key = { it.id }) { session ->
            GlassCard {
                Text(session.label ?: session.sensorKind, style = MaterialTheme.typography.titleSmall)
                LabeledValue("Samples", session.sampleCount.toString())
                LabeledValue("Start", session.startDate.toString())
                LabeledValue("End", session.endDate.toString())
                LabeledValue("Device", "${session.deviceModel} · fw ${session.deviceFirmware}")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = {
                        scope.launch {
                            val csv = vm.exportCsv(session) ?: return@launch
                            val filename = ExportFilename.make(
                                deviceName = session.deviceModel.ifEmpty { "MetaWear" },
                                sensorTag = session.sensorKind,
                                timestamp = session.startDate,
                            )
                            CsvShare.share(context, filename, csv)
                        }
                    }) { Text("Export CSV") }
                    TextButton(onClick = { vm.deleteSession(session.id) }) { Text("Delete") }
                }
            }
        }
    }
}
