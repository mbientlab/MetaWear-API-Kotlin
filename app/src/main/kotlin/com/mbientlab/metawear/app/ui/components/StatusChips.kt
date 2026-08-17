package com.mbientlab.metawear.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Battery0Bar
import androidx.compose.material.icons.filled.Battery2Bar
import androidx.compose.material.icons.filled.Battery3Bar
import androidx.compose.material.icons.filled.Battery5Bar
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mbientlab.metawear.DeviceState
import com.mbientlab.metawear.app.core.BandwidthAdvisor
import com.mbientlab.metawear.app.ui.theme.Palette
import com.mbientlab.metawear.app.ui.theme.forText
import com.mbientlab.metawear.model.BatteryState

/** Compact tonal status indicator: tinted capsule with an icon and a short label. */
@Composable
fun StatusChip(
    text: String,
    tint: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = tint.copy(alpha = 0.15f),
        contentColor = tint.forText(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
            } else if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            }
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            trailing?.invoke()
        }
    }
}

/** How a device on the scan screen relates to this phone right now. */
sealed interface DeviceConnectionStatus {
    data class Connected(val rssi: Int?) : DeviceConnectionStatus
    data object Connecting : DeviceConnectionStatus
    data class Available(val rssi: Int?) : DeviceConnectionStatus
    data object Offline : DeviceConnectionStatus
}

/**
 * Scan-row status: connected (green, with bars), connecting (gold + spinner),
 * available (blue bars + dBm), or offline (gray).
 */
@Composable
fun DeviceStatusChip(status: DeviceConnectionStatus) {
    when (status) {
        is DeviceConnectionStatus.Connected -> StatusChip(
            text = "Connected",
            tint = Palette.success,
            icon = Icons.Filled.Circle,
            trailing = status.rssi?.let { { RssiBars(dBm = it, tint = Palette.success) } },
        )
        DeviceConnectionStatus.Connecting -> StatusChip(
            text = "Connecting…",
            tint = Palette.warning,
            leading = {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = Palette.warning,
                )
            },
        )
        is DeviceConnectionStatus.Available -> StatusChip(
            text = status.rssi?.let { "$it dBm" } ?: "Available",
            tint = Palette.info,
            leading = { RssiBars(dBm = status.rssi) },
        )
        DeviceConnectionStatus.Offline -> StatusChip(
            text = "Offline",
            tint = Palette.neutral,
            icon = Icons.Outlined.Circle,
        )
    }
}

/**
 * Four signal bars. Defaults to the info blue so the bars match the blue
 * capsule they usually sit in — deliberately NOT the brand orange.
 */
@Composable
fun RssiBars(dBm: Int?, tint: Color = Palette.info) {
    val bars = when {
        dBm == null -> 0
        dBm < -90 -> 1
        dBm < -75 -> 2
        dBm < -60 -> 3
        else -> 4
    }
    val off = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        repeat(4) { index ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((6 + index * 3).dp)
                    .background(if (index < bars) tint else off, CircleShape),
            )
        }
    }
}

/** Live link quality: bars + dBm in the info blue. */
@Composable
fun RssiChip(dBm: Int) {
    StatusChip(text = "$dBm dBm", tint = Palette.info, leading = { RssiBars(dBm) })
}

/**
 * Connection/activity state chip. [isLogging] forces the red "Logging" look
 * for a board that is recording autonomously while the SDK reports idle.
 */
@Composable
fun StateChip(state: DeviceState, isLogging: Boolean = false) {
    val (label, icon, tint) = when {
        isLogging -> Triple("Logging", Icons.Filled.FiberManualRecord, Palette.danger)
        state == DeviceState.Disconnected -> Triple("Disconnected", Icons.Filled.BluetoothDisabled, Palette.danger)
        state == DeviceState.Connecting -> Triple("Connecting…", Icons.Filled.BluetoothSearching, Palette.warning)
        state == DeviceState.Idle -> Triple("Idle", Icons.Outlined.CheckCircle, Palette.warning)
        state == DeviceState.Streaming -> Triple("Streaming", Icons.AutoMirrored.Filled.TrendingUp, Palette.info)
        state == DeviceState.Logging -> Triple("Logging", Icons.Filled.FiberManualRecord, Palette.danger)
        state is DeviceState.Downloading ->
            Triple("Downloading ${(state.progress * 100).toInt()}%", Icons.Filled.Download, Palette.accent)
        else -> Triple("—", Icons.Outlined.Circle, Palette.neutral)
    }
    StatusChip(text = label, tint = tint, icon = icon)
}

/** Battery charge chip, colored by level (coral < 15 %, gold < 35 %, green above). */
@Composable
fun BatteryChip(battery: BatteryState?) {
    val charge = battery?.charge
    val icon = when {
        charge == null || charge < 10 -> Icons.Filled.Battery0Bar
        charge < 25 -> Icons.Filled.Battery2Bar
        charge < 50 -> Icons.Filled.Battery3Bar
        charge < 75 -> Icons.Filled.Battery5Bar
        else -> Icons.Filled.BatteryFull
    }
    val tint = when {
        charge == null -> Palette.neutral
        charge < 15 -> Palette.danger
        charge < 35 -> Palette.warning
        else -> Palette.success
    }
    StatusChip(text = charge?.let { "$it%" } ?: "—", tint = tint, icon = icon)
}

/**
 * Small tonal pill for secondary facts (model name, firmware) in a header.
 * [emphasized] switches to the primary-container tone (brand orange tint).
 */
@Composable
fun InfoPill(text: String, emphasized: Boolean = false, monospace: Boolean = false) {
    Surface(
        shape = CircleShape,
        color = if (emphasized) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (emphasized) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = if (monospace) FontFamily.Monospace else null,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/**
 * Bandwidth advisor: shown only when the combined sample rate exceeds the
 * BLE ceiling, with a one-tap "halve all rates" remedy.
 */
@Composable
fun BandwidthCard(aggregateHz: Double, onHalve: () -> Unit) {
    if (aggregateHz <= BandwidthAdvisor.BLE_SAFE_CEILING_HZ) return
    BrandCard(containerColor = Palette.warning.copy(alpha = 0.15f)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = Palette.warning)
            Column(Modifier.weight(1f)) {
                Text("Combined rate ${aggregateHz.toInt()} Hz", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Above BLE limit — samples may drop.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(onClick = onHalve) { Text("Halve rates") }
        }
    }
}
