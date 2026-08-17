package com.mbientlab.metawear.app.ui.device

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mbientlab.metawear.DeviceState
import com.mbientlab.metawear.app.data.LogSessionRecord
import com.mbientlab.metawear.app.ui.appContainer
import com.mbientlab.metawear.app.ui.appViewModel
import com.mbientlab.metawear.app.ui.components.AppScaffold
import com.mbientlab.metawear.app.ui.components.BatteryChip
import com.mbientlab.metawear.app.ui.components.BrandCard
import com.mbientlab.metawear.app.ui.components.BrandCardShape
import com.mbientlab.metawear.app.ui.components.InfoPill
import com.mbientlab.metawear.app.ui.components.Notice
import com.mbientlab.metawear.app.ui.components.StateChip
import com.mbientlab.metawear.app.ui.theme.Palette
import com.mbientlab.metawear.app.vm.DeviceViewModel

/** One activity tile of the device hub. */
private data class ActivityTile(val title: String, val icon: ImageVector, val route: String)

private val activityTiles = listOf(
    ActivityTile("Live Stream", Icons.AutoMirrored.Filled.TrendingUp, "stream"),
    ActivityTile("Logging", Icons.Filled.RadioButtonChecked, "logging"),
    ActivityTile("Controls", Icons.Filled.Tune, "controls"),
    ActivityTile("Device Info", Icons.Outlined.Info, "info"),
    ActivityTile("Settings", Icons.Outlined.Settings, "settings"),
    ActivityTile("Session History", Icons.Filled.History, "sessions"),
)

/**
 * Device hub: identity header (MAC, state, battery, model, firmware) above a
 * grid of activity tiles. Identify/Disconnect live in the top bar.
 */
@Composable
fun DeviceDetailScreen(onNavigate: (String) -> Unit, onDisconnected: () -> Unit, onBack: () -> Unit) {
    val vm = appViewModel(::DeviceViewModel)
    val container = appContainer()
    val state by vm.state.collectAsState()
    val info by vm.deviceInfo.collectAsState()
    val battery by vm.battery.collectAsState()
    val mac by vm.macAddress.collectAsState()
    val isConnecting by vm.isConnecting.collectAsState()
    val lastError by vm.lastError.collectAsState()
    val foreignLog by vm.foreignLog.collectAsState()
    val foreignStatus by vm.foreignStatus.collectAsState()
    val records by container.logSessions.records.collectAsState()

    val deviceId = vm.device?.identifier
    val isDeviceLogging = records.any {
        it.deviceId == deviceId && it.status == LogSessionRecord.Status.RUNNING
    }
    val notice = lastError?.let { Notice(it) { vm.clearError() } }
        ?: foreignStatus?.let { Notice(it) { vm.clearForeignStatus() } }

    Box(Modifier.fillMaxSize()) {
        AppScaffold(
            title = vm.displayName,
            onBack = onBack,
            notice = notice,
            actions = {
                IconButton(onClick = { vm.identify() }, enabled = state != DeviceState.Disconnected) {
                    Icon(Icons.Outlined.Lightbulb, contentDescription = "Identify")
                }
                IconButton(
                    onClick = {
                        vm.disconnect()
                        onDisconnected()
                    },
                    enabled = state != DeviceState.Disconnected,
                ) {
                    Icon(Icons.Outlined.Cancel, contentDescription = "Disconnect", tint = Palette.danger)
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
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    BrandCard(verticalSpacing = 12.dp) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                mac ?: deviceId ?: "—",
                                style = MaterialTheme.typography.titleSmall,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            StateChip(state = state, isLogging = isDeviceLogging)
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BatteryChip(battery)
                            info?.let { device ->
                                InfoPill(device.model.displayName, emphasized = true)
                                InfoPill("fw ${device.firmwareRevision}", monospace = true)
                            }
                        }
                        if (state == DeviceState.Disconnected && !isConnecting) {
                            OutlinedButton(onClick = { vm.reconnect() }) { Text("Reconnect") }
                        }
                    }
                }

                foreignLog?.let { orphan ->
                    item {
                        BrandCard {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    if (orphan.isActivelyLogging) Icons.Filled.RadioButtonChecked else Icons.Filled.Inbox,
                                    contentDescription = null,
                                    tint = Palette.accent,
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        if (orphan.isActivelyLogging) "Logging in progress from another device"
                                        else "Data on this board",
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        when {
                                            orphan.isActivelyLogging && orphan.entryCount > 0 ->
                                                "${orphan.entryCount} entries so far"
                                            orphan.isActivelyLogging ->
                                                "Recording — first entries are still buffering to flash"
                                            else -> "${orphan.entryCount} entries recorded"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Text(
                                "Downloading saves the recording to Session History" +
                                    (if (orphan.isActivelyLogging) " and stops the logging." else ".") +
                                    " Sensor types are reconstructed from the board's own logger metadata.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(onClick = { vm.downloadForeignLog() }) {
                                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (orphan.isActivelyLogging) "Stop & Download" else "Download")
                                }
                                TextButton(
                                    onClick = { vm.discardForeignLog() },
                                    colors = ButtonDefaults.textButtonColors(contentColor = Palette.danger),
                                ) {
                                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Discard")
                                }
                            }
                        }
                    }
                }

                items(activityTiles.chunked(2).size) { rowIndex ->
                    val pair = activityTiles.chunked(2)[rowIndex]
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { tile ->
                            ActivityTileCard(
                                tile = tile,
                                modifier = Modifier.weight(1f),
                                onClick = { onNavigate(tile.route) },
                            )
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        if (isConnecting) {
            ConnectingOverlay(deviceName = vm.displayName)
        }
    }
}

/** Activity tile: brand-orange icon over a title, one color across the grid. */
@Composable
private fun ActivityTileCard(tile: ActivityTile, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = modifier.heightIn(min = 120.dp),
        shape = BrandCardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(tile.icon, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(28.dp))
            Text(tile.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * Scrim shown while the BLE handshake runs. Absorbs taps so a second
 * connection can't be started against a device that isn't ready yet.
 */
@Composable
private fun ConnectingOverlay(deviceName: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        contentAlignment = Alignment.Center,
    ) {
        Card(shape = MaterialTheme.shapes.large) {
            Column(
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                CircularProgressIndicator()
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Connecting", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        deviceName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
