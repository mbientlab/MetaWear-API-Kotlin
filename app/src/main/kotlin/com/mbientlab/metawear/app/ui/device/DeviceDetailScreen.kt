package com.mbientlab.metawear.app.ui.device

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mbientlab.metawear.DeviceState
import com.mbientlab.metawear.app.ui.appViewModel
import com.mbientlab.metawear.app.ui.components.ErrorBanner
import com.mbientlab.metawear.app.ui.components.GlassCard
import com.mbientlab.metawear.app.ui.components.LabeledValue
import com.mbientlab.metawear.app.ui.components.SectionHeader
import com.mbientlab.metawear.app.ui.components.StateBadge
import com.mbientlab.metawear.app.ui.theme.GlassTextDim
import com.mbientlab.metawear.app.vm.DeviceViewModel

/** One navigable feature row of the device hub. */
data class FeatureDestination(val title: String, val subtitle: String, val route: String)

private val features = listOf(
    FeatureDestination("Live Stream", "Charts, xyz readout, effective Hz", "stream"),
    FeatureDestination("Logging", "Record to flash, download sessions", "logging"),
    FeatureDestination("Sessions", "History and CSV export", "sessions"),
    FeatureDestination("Controls", "LED patterns and haptic pulses", "controls"),
    FeatureDestination("Device Info", "Model, firmware, modules", "info"),
    FeatureDestination("Settings", "Name, advertising, factory reset", "settings"),
    FeatureDestination("Firmware", "Check and install updates", "firmware"),
)

@Composable
fun DeviceDetailScreen(onNavigate: (String) -> Unit, onDisconnected: () -> Unit) {
    val vm = appViewModel(::DeviceViewModel)
    val state by vm.state.collectAsState()
    val info by vm.deviceInfo.collectAsState()
    val battery by vm.battery.collectAsState()
    val isConnecting by vm.isConnecting.collectAsState()
    val lastError by vm.lastError.collectAsState()
    val foreignLog by vm.foreignLog.collectAsState()
    val foreignStatus by vm.foreignStatus.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(vm.displayName, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        vm.device?.identifier ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = GlassTextDim,
                    )
                }
                if (isConnecting) CircularProgressIndicator(Modifier.padding(4.dp)) else StateBadge(state)
            }
        }

        item { ErrorBanner(lastError) { vm.clearError() } }

        foreignLog?.let { orphan ->
            item {
                GlassCard {
                    Text("Log from another session", style = MaterialTheme.typography.titleSmall)
                    Text(
                        buildString {
                            append("This board carries a log this app didn't start")
                            if (orphan.entryCount > 0) append(" (${orphan.entryCount} entries)")
                            if (orphan.isActivelyLogging) append(" — and it is still recording")
                            append(". Recover it into session history, or discard it.")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = GlassTextDim,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { vm.downloadForeignLog() }) { Text("Download") }
                        TextButton(onClick = { vm.discardForeignLog() }) { Text("Discard") }
                    }
                }
            }
        }
        foreignStatus?.let { status ->
            item {
                GlassCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            status,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { vm.clearForeignStatus() }) { Text("OK") }
                    }
                }
            }
        }

        item {
            GlassCard {
                LabeledValue("Model", info?.modelNumber ?: "—")
                LabeledValue("Firmware", info?.firmwareRevision ?: "—")
                LabeledValue(
                    "Battery",
                    battery?.let { "${it.charge}% · ${it.voltage} mV" } ?: "—",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.identify() }) { Text("Identify (LED)") }
                    if (state == DeviceState.Disconnected) {
                        TextButton(onClick = { vm.reconnect() }) { Text("Reconnect") }
                    } else {
                        TextButton(onClick = {
                            vm.disconnect()
                            onDisconnected()
                        }) { Text("Disconnect") }
                    }
                }
            }
        }

        item { SectionHeader("Features") }
        items(features.size) { index ->
            val feature = features[index]
            GlassCard(modifier = Modifier.clickable { onNavigate(feature.route) }) {
                Text(feature.title, style = MaterialTheme.typography.titleSmall)
                Text(feature.subtitle, style = MaterialTheme.typography.bodySmall, color = GlassTextDim)
            }
        }
    }
}
