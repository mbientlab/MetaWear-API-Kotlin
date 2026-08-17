package com.mbientlab.metawear.app.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mbientlab.metawear.app.ui.appViewModel
import com.mbientlab.metawear.app.ui.components.ActionRow
import com.mbientlab.metawear.app.ui.components.AppScaffold
import com.mbientlab.metawear.app.ui.components.BrandCard
import com.mbientlab.metawear.app.ui.components.GroupCard
import com.mbientlab.metawear.app.ui.components.Notice
import com.mbientlab.metawear.app.ui.components.SectionFooter
import com.mbientlab.metawear.app.ui.components.SectionHeader
import com.mbientlab.metawear.app.ui.firmware.FirmwareSection
import com.mbientlab.metawear.app.ui.theme.Palette
import com.mbientlab.metawear.app.vm.FirmwareUpdateViewModel
import com.mbientlab.metawear.app.vm.SettingsViewModel
import com.mbientlab.metawear.sensor.Settings

/**
 * Device settings: rename, firmware, advertising, maintenance, factory
 * reset. Every destructive action confirms through a centered dialog.
 */
@Composable
fun SettingsScreen(onFactoryReset: () -> Unit, onBack: () -> Unit) {
    val vm = appViewModel(::SettingsViewModel)
    val statusMessage by vm.statusMessage.collectAsState()
    val lastError by vm.lastError.collectAsState()
    val didFactoryReset by vm.didFactoryReset.collectAsState()

    val firmwareVm = appViewModel(::FirmwareUpdateViewModel)

    var name by remember { mutableStateOf("") }
    var intervalMs by remember { mutableStateOf("417") }
    var timeoutSec by remember { mutableStateOf("0") }
    var txPower by remember { mutableStateOf(Settings.TxPower.ZERO) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showClearMacrosDialog by remember { mutableStateOf(false) }
    var showClearEventsDialog by remember { mutableStateOf(false) }
    var showRestartDialog by remember { mutableStateOf(false) }

    LaunchedEffect(didFactoryReset) {
        // Board rebooted; return to the device hub, which offers Reconnect.
        if (didFactoryReset) onFactoryReset()
    }

    val notice = lastError?.let { Notice(it) { vm.clearMessages() } }
        ?: statusMessage?.let { Notice(it) { vm.clearMessages() } }

    AppScaffold(title = "Settings", onBack = onBack, notice = notice) { padding ->
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
            item { SectionHeader("Rename") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BrandCard(verticalSpacing = 10.dp) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Device name") },
                            singleLine = true,
                            isError = name.isNotEmpty() && !vm.isNameValid(name),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        FilledTonalButton(
                            onClick = { vm.rename(name) },
                            enabled = vm.isNameValid(name),
                        ) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 6.dp),
                            )
                            Text("Save")
                        }
                    }
                    SectionFooter(
                        "Up to 26 characters: letters, numbers, spaces, _ and -. The name updates in the app " +
                            "immediately and on the board's next advertisement.",
                    )
                }
            }

            item { SectionHeader("Firmware") }
            item { FirmwareSection(firmwareVm) }

            item { SectionHeader("Advertising") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BrandCard(verticalSpacing = 10.dp) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = intervalMs,
                                onValueChange = { intervalMs = it.filter(Char::isDigit).take(5) },
                                label = { Text("Interval (ms)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = timeoutSec,
                                onValueChange = { timeoutSec = it.filter(Char::isDigit).take(3) },
                                label = { Text("Timeout (s)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "TX power",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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
                        FilledTonalButton(
                            onClick = {
                                vm.setAdvertising(
                                    intervalMs = intervalMs.toIntOrNull() ?: 417,
                                    timeoutSec = timeoutSec.toIntOrNull() ?: 0,
                                )
                            },
                        ) { Text("Apply") }
                    }
                    SectionFooter("Interval 20–10240 ms; timeout 0 advertises forever. TX power applies immediately.")
                }
            }

            item { SectionHeader("Maintenance") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    GroupCard {
                        ActionRow("Reset LED", Icons.Filled.FlashlightOff, onClick = { vm.resetLed() })
                        HorizontalDivider()
                        ActionRow(
                            "Clear Macros",
                            Icons.Filled.Memory,
                            tint = Palette.danger,
                            onClick = { showClearMacrosDialog = true },
                        )
                        HorizontalDivider()
                        ActionRow(
                            "Clear Events & Timers",
                            Icons.Filled.EventBusy,
                            tint = Palette.danger,
                            onClick = { showClearEventsDialog = true },
                        )
                        HorizontalDivider()
                        ActionRow("Restart Board", Icons.Filled.Refresh, onClick = { showRestartDialog = true })
                    }
                    SectionFooter(
                        "Reset LED stops and clears the light immediately — if it relights after a disconnect, " +
                            "an on-board event is re-arming it; use Clear Events & Timers. Restart reboots the " +
                            "board without erasing logs or macros.",
                    )
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    GroupCard {
                        ActionRow(
                            "Factory Reset",
                            Icons.Filled.Warning,
                            tint = Palette.danger,
                            onClick = { showResetDialog = true },
                        )
                    }
                    SectionFooter("Erases all logs, processors, events, macros, and timers on the board, then reboots it.")
                }
            }
        }
    }

    if (showClearMacrosDialog) {
        ConfirmDialog(
            title = "Clear all macros on this board?",
            text = "Removes every on-boot macro — including the identity broadcast this app configures. " +
                "The app re-applies its broadcast automatically on the next connect.",
            confirmLabel = "Clear Macros",
            destructive = true,
            onConfirm = {
                showClearMacrosDialog = false
                vm.clearMacros()
            },
            onDismiss = { showClearMacrosDialog = false },
        )
    }

    if (showClearEventsDialog) {
        ConfirmDialog(
            title = "Clear all events and timers?",
            text = "Removes every on-board event binding and timer — from ALL apps, including this one's " +
                "recording heartbeat. Use this if the LED keeps relighting after disconnects.",
            confirmLabel = "Clear Events & Timers",
            destructive = true,
            onConfirm = {
                showClearEventsDialog = false
                vm.clearEvents()
            },
            onDismiss = { showClearEventsDialog = false },
        )
    }

    if (showRestartDialog) {
        ConfirmDialog(
            title = "Restart this MetaWear?",
            text = "Reboots the board without erasing anything — logs and macros survive. The board will disconnect.",
            confirmLabel = "Restart",
            destructive = false,
            onConfirm = {
                showRestartDialog = false
                vm.restart()
            },
            onDismiss = { showRestartDialog = false },
        )
    }

    if (showResetDialog) {
        ConfirmDialog(
            title = "Factory reset this MetaWear?",
            text = "This will erase all on-device state. The board will reboot and disconnect.",
            confirmLabel = "Reset",
            destructive = true,
            onConfirm = {
                showResetDialog = false
                vm.factoryReset()
            },
            onDismiss = { showResetDialog = false },
        )
    }
}

/** Centered confirmation dialog; the confirm action reads red when destructive. */
@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    destructive: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (destructive) Palette.danger else LocalContentColor.current)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
