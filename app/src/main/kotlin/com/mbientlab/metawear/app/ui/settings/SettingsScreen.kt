package com.mbientlab.metawear.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mbientlab.metawear.app.ui.appViewModel
import com.mbientlab.metawear.app.ui.components.ErrorBanner
import com.mbientlab.metawear.app.ui.components.GlassCard
import com.mbientlab.metawear.app.ui.components.SectionHeader
import com.mbientlab.metawear.app.ui.theme.GlassError
import com.mbientlab.metawear.app.ui.theme.GlassGood
import com.mbientlab.metawear.app.ui.theme.GlassTextDim
import com.mbientlab.metawear.app.vm.SettingsViewModel
import com.mbientlab.metawear.sensor.Settings

@Composable
fun SettingsScreen(onFactoryReset: () -> Unit) {
    val vm = appViewModel(::SettingsViewModel)
    val statusMessage by vm.statusMessage.collectAsState()
    val lastError by vm.lastError.collectAsState()
    val didFactoryReset by vm.didFactoryReset.collectAsState()

    var name by remember { mutableStateOf("") }
    var intervalMs by remember { mutableStateOf("417") }
    var timeoutSec by remember { mutableStateOf("0") }
    var txPower by remember { mutableStateOf(Settings.TxPower.ZERO) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showClearMacrosDialog by remember { mutableStateOf(false) }
    var showClearEventsDialog by remember { mutableStateOf(false) }
    var showRestartDialog by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(didFactoryReset) {
        // Board rebooted; return to the device hub, which offers Reconnect.
        if (didFactoryReset) onFactoryReset()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text("Settings", style = MaterialTheme.typography.headlineSmall) }
        item { ErrorBanner(lastError) { vm.clearMessages() } }
        statusMessage?.let { message ->
            item {
                GlassCard { Text(message, color = GlassGood, style = MaterialTheme.typography.bodySmall) }
            }
        }

        item { SectionHeader("Device name") }
        item {
            GlassCard {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Advertising name (max 26 ASCII chars)") },
                    isError = name.isNotEmpty() && !vm.isNameValid(name),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { vm.rename(name) },
                    enabled = vm.isNameValid(name),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save Name") }
            }
        }

        item { SectionHeader("Advertising") }
        item {
            GlassCard {
                OutlinedTextField(
                    value = intervalMs,
                    onValueChange = { intervalMs = it.filter(Char::isDigit).take(5) },
                    label = { Text("Interval (ms, 20–10240)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = timeoutSec,
                    onValueChange = { timeoutSec = it.filter(Char::isDigit).take(3) },
                    label = { Text("Timeout (s, 0 = forever)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("TX power", style = MaterialTheme.typography.labelMedium, color = GlassTextDim)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Settings.TxPower.entries.forEach { power ->
                        FilterChip(
                            selected = txPower == power,
                            onClick = {
                                txPower = power
                                vm.setTxPower(power)
                            },
                            label = { Text("${power.raw} dBm") },
                        )
                    }
                }
                Button(
                    onClick = {
                        vm.setAdvertising(
                            intervalMs = intervalMs.toIntOrNull() ?: 417,
                            timeoutSec = timeoutSec.toIntOrNull() ?: 0,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Apply Advertising") }
            }
        }

        item { SectionHeader("Maintenance") }
        item {
            GlassCard {
                Button(
                    onClick = { vm.resetLed() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Reset LED") }
                Button(
                    onClick = { showClearMacrosDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Clear Macros…") }
                Button(
                    onClick = { showClearEventsDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Clear Events & Timers…") }
                Button(
                    onClick = { showRestartDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Restart Board…") }
                Text(
                    "Reset LED stops and clears the light immediately — if it relights after a " +
                        "disconnect, an on-board event is re-arming it; use Clear Events & Timers. " +
                        "Restart reboots the board without erasing logs or macros.",
                    style = MaterialTheme.typography.bodySmall,
                    color = GlassTextDim,
                )
            }
        }

        item { SectionHeader("Danger zone") }
        item {
            GlassCard {
                Text(
                    "Factory reset erases all on-board log entries, loggers, events, " +
                        "data processors, and macros, then reboots the board.",
                    style = MaterialTheme.typography.bodySmall,
                    color = GlassTextDim,
                )
                Button(
                    onClick = { showResetDialog = true },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = GlassError),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Factory Reset…") }
            }
        }
    }

    if (showClearMacrosDialog) {
        AlertDialog(
            onDismissRequest = { showClearMacrosDialog = false },
            title = { Text("Clear macros?") },
            text = { Text("Removes every on-boot macro recorded on the board.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearMacrosDialog = false
                    vm.clearMacros()
                }) { Text("Clear Macros", color = GlassError) }
            },
            dismissButton = {
                TextButton(onClick = { showClearMacrosDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showClearEventsDialog) {
        AlertDialog(
            onDismissRequest = { showClearEventsDialog = false },
            title = { Text("Clear events & timers?") },
            text = {
                Text(
                    "Removes every on-board event binding and timer — from ALL apps, including " +
                        "this one's recording heartbeat. Use this if the LED keeps relighting " +
                        "after disconnects.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearEventsDialog = false
                    vm.clearEvents()
                }) { Text("Clear Events & Timers", color = GlassError) }
            },
            dismissButton = {
                TextButton(onClick = { showClearEventsDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showRestartDialog) {
        AlertDialog(
            onDismissRequest = { showRestartDialog = false },
            title = { Text("Restart the board?") },
            text = {
                Text(
                    "Reboots the board without erasing anything — logs and macros survive. " +
                        "The board will disconnect.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRestartDialog = false
                    vm.restart()
                }) { Text("Restart") }
            },
            dismissButton = {
                TextButton(onClick = { showRestartDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Factory reset?") },
            text = {
                Text(
                    "All data on the board (logs, loggers, macros) will be erased and the " +
                        "board will reboot. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showResetDialog = false
                    vm.factoryReset()
                }) { Text("Reset", color = GlassError) }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("Cancel") }
            },
        )
    }
}
