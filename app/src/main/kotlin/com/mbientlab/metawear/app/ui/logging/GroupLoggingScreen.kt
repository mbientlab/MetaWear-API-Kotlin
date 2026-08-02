package com.mbientlab.metawear.app.ui.logging

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mbientlab.metawear.app.core.SensorKey
import com.mbientlab.metawear.app.core.SensorSelection
import com.mbientlab.metawear.app.data.LogSessionRecord
import com.mbientlab.metawear.app.ui.appContainer
import com.mbientlab.metawear.app.ui.components.GlassCard
import com.mbientlab.metawear.app.ui.components.SectionHeader
import com.mbientlab.metawear.app.ui.stream.SensorConfigSection
import com.mbientlab.metawear.app.ui.theme.GlassError
import com.mbientlab.metawear.app.ui.theme.GlassGood
import com.mbientlab.metawear.app.ui.theme.GlassTextDim
import com.mbientlab.metawear.app.ui.theme.GlassWarn
import com.mbientlab.metawear.app.vm.GroupCaptureCoordinator
import kotlinx.coroutines.launch

/**
 * Fleet logging: pick boards, arm one shared sensor config across all of
 * them under a single group id, then stop + download the whole batch. The
 * walk is sequential (one radio) and survives navigation — the coordinator
 * lives on the app container.
 */
@Composable
fun GroupLoggingScreen() {
    val container = appContainer()
    val coordinator = container.groupCapture

    val boards by coordinator.boards.collectAsState()
    val isBusy by coordinator.isBusy.collectAsState()
    val lastPass by coordinator.lastPass.collectAsState()
    val demoMode by container.demoModeEnabled.collectAsState()
    val remembered by container.remembered.devices.collectAsState()
    val records by container.logSessions.records.collectAsState()

    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var selections by remember { mutableStateOf(listOf(SensorSelection(SensorKey.ACCELEROMETER))) }
    var showStopDialog by remember { mutableStateOf(false) }

    // Candidates: remembered boards, plus the demo fleet when demo mode is on.
    val candidates = buildList {
        if (demoMode) {
            container.demoFleet.forEachIndexed { index, device ->
                add(device.identifier to container.demoName(index))
            }
        }
        remembered.forEach { device ->
            if (none { it.first == device.mac }) add(device.mac to device.name)
        }
    }

    fun members(ids: Collection<String>): List<GroupCaptureCoordinator.Member> = ids.map { id ->
        GroupCaptureCoordinator.Member(
            device = container.device(id),
            name = container.displayNameFor(id) ?: id,
        )
    }

    // Records with a group id are the durable "fleet recording" signal
    // (they survive app restarts).
    val activeGroupRecords = records.filter {
        it.groupID != null &&
            (it.status == LogSessionRecord.Status.RUNNING || it.status == LogSessionRecord.Status.STOPPED)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text("Group Logging", style = MaterialTheme.typography.headlineSmall) }
        item {
            Text(
                "Log the same sensors across several boards at once. Boards are armed one at " +
                    "a time; each keeps recording on its own until you collect.",
                style = MaterialTheme.typography.bodySmall,
                color = GlassTextDim,
            )
        }

        item { SectionHeader("Boards") }
        if (candidates.isEmpty()) {
            item {
                GlassCard {
                    Text(
                        "No boards yet — connect to a board once (or enable demo mode) so it " +
                            "appears here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = GlassTextDim,
                    )
                }
            }
        }
        items(candidates, key = { it.first }) { (id, name) ->
            GlassCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.titleSmall)
                        Text(id, style = MaterialTheme.typography.bodySmall, color = GlassTextDim)
                        if (records.any {
                                it.deviceId == id &&
                                    (
                                        it.status == LogSessionRecord.Status.RUNNING ||
                                            it.status == LogSessionRecord.Status.STOPPED
                                        )
                            }
                        ) {
                            Text(
                                "Has a session waiting",
                                style = MaterialTheme.typography.labelSmall,
                                color = GlassWarn,
                            )
                        }
                    }
                    Checkbox(
                        checked = id in selectedIds,
                        onCheckedChange = { checked ->
                            selectedIds = if (checked) selectedIds + id else selectedIds - id
                        },
                    )
                }
            }
        }

        item { SectionHeader("Shared sensors") }
        item {
            SensorConfigSection(
                modules = emptyMap(),   // fleet-wide config: no single module table to gate on
                selections = selections,
                onSelectionsChange = { selections = it },
                loggingMode = true,
                // The multichannel thermometer and the humidity readable vary
                // per board generation — keep the shared group config to the
                // kinds every board runs identically.
                excludeKeys = setOf(SensorKey.TEMPERATURE, SensorKey.HUMIDITY),
            )
        }

        item {
            Button(
                onClick = {
                    val chosen = members(selectedIds)
                    container.appScope.launch { coordinator.startAll(chosen, selections) }
                },
                enabled = !isBusy && selectedIds.isNotEmpty() && selections.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Start Logging All (${selectedIds.size})") }
        }

        if (activeGroupRecords.isNotEmpty()) {
            item { SectionHeader("Recording group") }
            item {
                GlassCard {
                    Text(
                        "${activeGroupRecords.map { it.deviceId }.distinct().size} board(s) have " +
                            "group sessions pending.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = { showStopDialog = true },
                        enabled = !isBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Stop & Download All") }
                }
            }
        }

        if (boards.isNotEmpty()) {
            item { SectionHeader("Progress") }
            items(boards, key = { it.id }) { board ->
                GlassCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(board.name, style = MaterialTheme.typography.titleSmall)
                        PhaseText(board.phase)
                    }
                }
            }
            val failed = boards.filter { it.phase is GroupCaptureCoordinator.BoardPhase.Failed }
            if (failed.isNotEmpty() && !isBusy && lastPass == GroupCaptureCoordinator.PassKind.COLLECT) {
                item {
                    TextButton(onClick = {
                        val retry = members(failed.map { it.id })
                        container.appScope.launch { coordinator.stopAndDownloadAll(retry) }
                    }) { Text("Retry Failed Download(s)") }
                }
            }
        }
    }

    if (showStopDialog) {
        AlertDialog(
            onDismissRequest = { showStopDialog = false },
            title = { Text("Stop & download all?") },
            text = {
                Text(
                    "Every board in the group stops recording, then each is downloaded in turn. " +
                        "Keep the boards nearby.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showStopDialog = false
                    val ids = activeGroupRecords.map { it.deviceId }.distinct()
                    container.appScope.launch { coordinator.stopAndDownloadAll(members(ids)) }
                }) { Text("Stop & Download") }
            },
            dismissButton = {
                TextButton(onClick = { showStopDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun PhaseText(phase: GroupCaptureCoordinator.BoardPhase) {
    val (text, color) = when (phase) {
        GroupCaptureCoordinator.BoardPhase.Pending -> "Waiting…" to GlassTextDim
        GroupCaptureCoordinator.BoardPhase.Connecting -> "Connecting…" to GlassTextDim
        GroupCaptureCoordinator.BoardPhase.Starting -> "Starting…" to GlassTextDim
        GroupCaptureCoordinator.BoardPhase.Verifying -> "Verifying…" to GlassTextDim
        GroupCaptureCoordinator.BoardPhase.Logging -> "Logging" to GlassGood
        GroupCaptureCoordinator.BoardPhase.Stopping -> "Stopping…" to GlassTextDim
        GroupCaptureCoordinator.BoardPhase.Downloading -> "Downloading…" to GlassTextDim
        is GroupCaptureCoordinator.BoardPhase.Saved -> "Saved ${phase.count} session(s)" to GlassGood
        is GroupCaptureCoordinator.BoardPhase.SavedWithIssues ->
            "Saved ${phase.count} — ${phase.message}" to GlassWarn
        is GroupCaptureCoordinator.BoardPhase.Skipped -> phase.message to GlassWarn
        is GroupCaptureCoordinator.BoardPhase.Failed -> phase.message to GlassError
    }
    Text(text, style = MaterialTheme.typography.labelMedium, color = color)
}
