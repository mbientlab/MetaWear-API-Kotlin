package com.mbientlab.metawear.app.ui.logging

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mbientlab.metawear.app.core.SensorKey
import com.mbientlab.metawear.app.core.SensorSelection
import com.mbientlab.metawear.app.data.LogSessionRecord
import com.mbientlab.metawear.app.ui.appContainer
import com.mbientlab.metawear.app.ui.components.ActionRow
import com.mbientlab.metawear.app.ui.components.AppScaffold
import com.mbientlab.metawear.app.ui.components.GroupCard
import com.mbientlab.metawear.app.ui.components.RssiChip
import com.mbientlab.metawear.app.ui.components.SectionFooter
import com.mbientlab.metawear.app.ui.components.SectionHeader
import com.mbientlab.metawear.app.ui.components.cardListItemColors
import com.mbientlab.metawear.app.ui.components.formatClock
import com.mbientlab.metawear.app.ui.stream.SensorConfigSection
import com.mbientlab.metawear.app.ui.theme.Palette
import com.mbientlab.metawear.app.ui.theme.forText
import com.mbientlab.metawear.app.vm.GroupCaptureCoordinator
import kotlinx.coroutines.launch

/**
 * Fleet logging: pick boards, arm one shared sensor config across all of
 * them under a single group id, then stop + download the whole batch. The
 * walk is sequential (one radio) and survives navigation — the coordinator
 * lives on the app container.
 */
@Composable
fun GroupLoggingScreen(onBack: () -> Unit, onSessionHistory: () -> Unit) {
    val container = appContainer()
    val coordinator = container.groupCapture

    val boards by coordinator.boards.collectAsState()
    val isBusy by coordinator.isBusy.collectAsState()
    val lastPass by coordinator.lastPass.collectAsState()
    val demoMode by container.demoModeEnabled.collectAsState()
    val remembered by container.remembered.devices.collectAsState()
    val records by container.logSessions.records.collectAsState()
    val rssiById by container.scanner.advertisementRssi.collectAsState()

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

    fun isPending(status: LogSessionRecord.Status) =
        status == LogSessionRecord.Status.RUNNING || status == LogSessionRecord.Status.STOPPED

    // Records with a group id are the durable "fleet recording" signal
    // (they survive app restarts).
    val activeGroupRecords = records.filter { it.groupID != null && isPending(it.status) }
    val activeByBoard = activeGroupRecords.groupBy { it.deviceId }
        .entries.sortedBy { entry -> entry.value.minOf { it.startDate } }
    val anyRunning = activeGroupRecords.any { it.status == LogSessionRecord.Status.RUNNING }

    // Hide "Last Run" when it would only duplicate the active-group section
    // (a fully successful start pass: every board it lists is already shown,
    // recording, under "Logging In Progress").
    val lastRunIsRedundant = !isBusy && activeGroupRecords.isNotEmpty() &&
        boards.all { it.phase == GroupCaptureCoordinator.BoardPhase.Logging }
    val showProgress = (isBusy || boards.isNotEmpty()) && !lastRunIsRedundant
    val failed = boards.filter { it.phase is GroupCaptureCoordinator.BoardPhase.Failed }
    val savedAny = boards.any {
        it.phase is GroupCaptureCoordinator.BoardPhase.Saved || it.phase is GroupCaptureCoordinator.BoardPhase.SavedWithIssues
    }

    AppScaffold(title = "Group Logging", onBack = onBack) { padding ->
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
            if (showProgress) {
                item { SectionHeader(if (isBusy) "Working…" else "Last Run") }
                item {
                    GroupCard {
                        boards.forEachIndexed { index, board ->
                            if (index > 0) HorizontalDivider()
                            ListItem(
                                headlineContent = { Text(board.name) },
                                supportingContent = { Text(phaseText(board.phase)) },
                                leadingContent = { PhaseIcon(board.phase) },
                                colors = cardListItemColors(),
                            )
                        }
                        if (!isBusy && savedAny) {
                            HorizontalDivider()
                            ActionRow("View Saved Sessions", Icons.Filled.History, onClick = onSessionHistory)
                        }
                        if (!isBusy && lastPass == GroupCaptureCoordinator.PassKind.COLLECT && failed.isNotEmpty()) {
                            HorizontalDivider()
                            ActionRow(
                                "Retry Failed Download${if (failed.size == 1) "" else "s"}",
                                Icons.Filled.Refresh,
                                onClick = {
                                    val retry = members(failed.map { it.id })
                                    container.appScope.launch { coordinator.stopAndDownloadAll(retry) }
                                },
                            )
                        }
                    }
                }
                if (!isBusy) {
                    item {
                        SectionFooter(
                            "Skipped boards keep their data — collect them here later, or connect to one " +
                                "directly and use its Logging screen.",
                        )
                    }
                }
            }

            if (activeGroupRecords.isNotEmpty()) {
                item { SectionHeader(if (anyRunning) "Logging In Progress" else "Ready To Collect") }
                item {
                    GroupCard {
                        activeByBoard.forEachIndexed { index, (deviceId, boardRecords) ->
                            if (index > 0) HorizontalDivider()
                            val isRunning = boardRecords.any { it.status == LogSessionRecord.Status.RUNNING }
                            ListItem(
                                headlineContent = { Text(container.displayNameFor(deviceId) ?: deviceId) },
                                supportingContent = {
                                    Text(
                                        if (isRunning) {
                                            "${boardRecords.size} sensor${if (boardRecords.size == 1) "" else "s"} · " +
                                                "since ${boardRecords.minOf { it.startDate }.formatClock()}"
                                        } else {
                                            "Stopped — awaiting download"
                                        },
                                    )
                                },
                                leadingContent = {
                                    Icon(
                                        if (isRunning) Icons.Filled.FiberManualRecord else Icons.Filled.PauseCircle,
                                        contentDescription = null,
                                        tint = if (isRunning) Palette.danger else Palette.warning,
                                    )
                                },
                                colors = cardListItemColors(),
                            )
                        }
                        HorizontalDivider()
                        ActionRow(
                            if (anyRunning) "Stop & Download All" else "Download All",
                            Icons.Filled.SaveAlt,
                            onClick = { showStopDialog = true },
                            enabled = !isBusy,
                        )
                    }
                }
                item {
                    SectionFooter(
                        "The boards record on their own — you can close the app or leave. " +
                            "Come back here to collect everything at once.",
                    )
                }
            } else if (!isBusy) {
                item { SectionHeader("Boards") }
                if (candidates.isEmpty()) {
                    item {
                        SectionFooter(
                            "No boards available — connect to a board once so it is remembered, or enable demo mode.",
                        )
                    }
                } else {
                    item {
                        GroupCard {
                            candidates.forEachIndexed { index, (id, name) ->
                                if (index > 0) HorizontalDivider()
                                val hasWaiting = records.any { it.deviceId == id && isPending(it.status) }
                                ListItem(
                                    headlineContent = { Text(name) },
                                    supportingContent = {
                                        Column {
                                            Text(id, fontFamily = FontFamily.Monospace)
                                            if (hasWaiting) {
                                                Text("Has a session waiting — download it first", color = Palette.warning.forText())
                                            }
                                        }
                                    },
                                    leadingContent = {
                                        Checkbox(
                                            checked = id in selectedIds,
                                            onCheckedChange = { checked ->
                                                selectedIds = if (checked) selectedIds + id else selectedIds - id
                                            },
                                        )
                                    },
                                    trailingContent = rssiById[id]?.let { rssi -> { RssiChip(rssi) } },
                                    colors = cardListItemColors(),
                                )
                            }
                        }
                    }
                }
                item {
                    SectionFooter(
                        "Every selected board records the same sensors. Boards are set up one at a time and keep " +
                            "logging on their own — no connection needed while they record.",
                    )
                }

                item {
                    SensorConfigSection(
                        modules = emptyMap(), // fleet-wide config: no single module table to gate on
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
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = {
                                val chosen = members(selectedIds)
                                container.appScope.launch { coordinator.startAll(chosen, selections) }
                            },
                            enabled = !isBusy && selectedIds.isNotEmpty() && selections.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = Palette.danger, contentColor = Color.White),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.FiberManualRecord, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Start Logging All")
                        }
                        SectionFooter(
                            if (selectedIds.isEmpty()) "Select at least one board above."
                            else "${selectedIds.size} board${if (selectedIds.size == 1) "" else "s"} will start logging.",
                        )
                    }
                }
            }
        }
    }

    if (showStopDialog) {
        AlertDialog(
            onDismissRequest = { showStopDialog = false },
            title = { Text("Stop logging on all boards and download their data?") },
            text = {
                Text(
                    "Each board is collected in turn. Boards that are out of range are skipped — " +
                        "their data stays on the board for later.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showStopDialog = false
                    val ids = activeGroupRecords.map { it.deviceId }.distinct()
                    container.appScope.launch { coordinator.stopAndDownloadAll(members(ids)) }
                }) { Text("Stop & Download All") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showStopDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun PhaseIcon(phase: GroupCaptureCoordinator.BoardPhase) {
    when (phase) {
        GroupCaptureCoordinator.BoardPhase.Pending ->
            Icon(Icons.Filled.RadioButtonUnchecked, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        GroupCaptureCoordinator.BoardPhase.Connecting,
        GroupCaptureCoordinator.BoardPhase.Starting,
        GroupCaptureCoordinator.BoardPhase.Verifying,
        GroupCaptureCoordinator.BoardPhase.Stopping,
        GroupCaptureCoordinator.BoardPhase.Downloading,
        -> CircularProgressIndicator(modifier = Modifier.padding(2.dp).size(20.dp), strokeWidth = 2.dp)
        GroupCaptureCoordinator.BoardPhase.Logging ->
            Icon(Icons.Filled.FiberManualRecord, contentDescription = null, tint = Palette.danger)
        is GroupCaptureCoordinator.BoardPhase.Saved ->
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Palette.success)
        is GroupCaptureCoordinator.BoardPhase.SavedWithIssues ->
            Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = Palette.warning)
        is GroupCaptureCoordinator.BoardPhase.Skipped ->
            Icon(Icons.Filled.DoNotDisturbOn, contentDescription = null, tint = Palette.warning)
        is GroupCaptureCoordinator.BoardPhase.Failed ->
            Icon(Icons.Filled.Warning, contentDescription = null, tint = Palette.danger)
    }
}

private fun phaseText(phase: GroupCaptureCoordinator.BoardPhase): String = when (phase) {
    GroupCaptureCoordinator.BoardPhase.Pending -> "Waiting…"
    GroupCaptureCoordinator.BoardPhase.Connecting -> "Connecting…"
    GroupCaptureCoordinator.BoardPhase.Starting -> "Starting loggers…"
    GroupCaptureCoordinator.BoardPhase.Verifying -> "Confirming data is recording…"
    GroupCaptureCoordinator.BoardPhase.Logging -> "Logging"
    GroupCaptureCoordinator.BoardPhase.Stopping -> "Stopping…"
    GroupCaptureCoordinator.BoardPhase.Downloading -> "Downloading…"
    is GroupCaptureCoordinator.BoardPhase.Saved -> "Saved ${phase.count} session${if (phase.count == 1) "" else "s"}"
    is GroupCaptureCoordinator.BoardPhase.SavedWithIssues ->
        "Saved ${phase.count} session${if (phase.count == 1) "" else "s"} · ${phase.message}"
    is GroupCaptureCoordinator.BoardPhase.Skipped -> phase.message
    is GroupCaptureCoordinator.BoardPhase.Failed -> phase.message
}
