package com.mbientlab.metawear.app.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mbientlab.metawear.DeviceState
import com.mbientlab.metawear.app.data.LogSessionRecord
import com.mbientlab.metawear.app.data.RememberedDevice
import com.mbientlab.metawear.app.ui.appContainer
import com.mbientlab.metawear.app.ui.appViewModel
import com.mbientlab.metawear.app.ui.components.AppScaffold
import com.mbientlab.metawear.app.ui.components.BrandCard
import com.mbientlab.metawear.app.ui.components.DeviceConnectionStatus
import com.mbientlab.metawear.app.ui.components.DeviceStatusChip
import com.mbientlab.metawear.app.ui.components.Notice
import com.mbientlab.metawear.app.ui.components.SectionHeader
import com.mbientlab.metawear.app.ui.components.cardListItemColors
import com.mbientlab.metawear.app.ui.theme.Palette
import com.mbientlab.metawear.app.vm.ScannerViewModel
import kotlinx.coroutines.flow.MutableStateFlow

/** Runtime permissions needed to scan/connect on this API level. */
private fun requiredBlePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

/**
 * Connection screen: remembered boards, nearby advertisers, the demo board,
 * and a link into session history — all on the brand orange field.
 */
@Composable
fun ScanScreen(
    onDeviceSelected: () -> Unit,
    onGroupLogging: () -> Unit = {},
    onSessionHistory: () -> Unit = {},
) {
    val vm = appViewModel(::ScannerViewModel)
    val container = appContainer()
    val context = LocalContext.current

    val isScanning by vm.isScanning.collectAsState()
    val nearbyAll by vm.nearbyDevices.collectAsState()
    val remembered by vm.remembered.collectAsState()
    val demoMode by vm.demoModeEnabled.collectAsState()
    val lastError by vm.lastError.collectAsState()
    val records by container.logSessions.records.collectAsState()
    val activeId by container.activeDeviceId.collectAsState()
    val activeState by remember(activeId) {
        activeId?.let { container.device(it).state } ?: MutableStateFlow(DeviceState.Disconnected)
    }.collectAsState()

    fun hasPermissions(): Boolean = requiredBlePermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    var permissionsGranted by remember { mutableStateOf(hasPermissions()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        permissionsGranted = grants.values.all { it }
        if (permissionsGranted) vm.startScan()
    }

    fun toggleScan() {
        when {
            isScanning -> vm.stopScan()
            hasPermissions() -> {
                permissionsGranted = true
                vm.startScan()
            }
            else -> permissionLauncher.launch(requiredBlePermissions())
        }
    }

    fun connect(identifier: String) {
        vm.stopScan()
        vm.select(identifier)
        onDeviceSelected()
    }

    val bluetoothAvailable = vm.isBluetoothAvailable()
    val demoRows = nearbyAll.filter { it.isDemo }
    val nearby = nearbyAll.filter { row -> !row.isDemo && remembered.none { it.mac == row.identifier } }
    val hasActiveGroup = records.any {
        it.groupID != null &&
            (it.status == LogSessionRecord.Status.RUNNING || it.status == LogSessionRecord.Status.STOPPED)
    }

    fun hasPendingLog(mac: String) = records.any {
        it.deviceId == mac &&
            (it.status == LogSessionRecord.Status.RUNNING || it.status == LogSessionRecord.Status.STOPPED)
    }

    fun status(device: RememberedDevice): DeviceConnectionStatus {
        val liveRssi = nearbyAll.firstOrNull { it.identifier == device.mac }?.rssi
        return when {
            activeId == device.mac && activeState == DeviceState.Connecting -> DeviceConnectionStatus.Connecting
            activeId == device.mac && activeState != DeviceState.Disconnected -> DeviceConnectionStatus.Connected(liveRssi)
            nearbyAll.any { it.identifier == device.mac } -> DeviceConnectionStatus.Available(liveRssi)
            else -> DeviceConnectionStatus.Offline
        }
    }

    Box(Modifier.fillMaxSize()) {
        BrandScanBackground()
        AppScaffold(
            title = "MetaWear",
            containerColor = Color.Transparent,
            notice = lastError?.let { Notice(it) { vm.clearError() } },
            topBarContentColor = Color.White,
            actions = {
                TextButton(
                    onClick = ::toggleScan,
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                ) {
                    Icon(
                        if (isScanning) Icons.Outlined.StopCircle else Icons.Filled.SettingsInputAntenna,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (isScanning) "Stop" else "Scan")
                }
                IconButton(onClick = onGroupLogging) {
                    Icon(
                        Icons.Filled.Layers,
                        contentDescription = "Group logging",
                        tint = if (hasActiveGroup) Palette.danger else Color.White,
                    )
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
                item { SectionHeader("Remembered", color = Color.White) }
                if (remembered.isEmpty()) {
                    item { ScanNote("No remembered devices yet.") }
                } else {
                    items(remembered, key = { "remembered-${it.mac}" }) { device ->
                        RememberedDeviceRow(
                            device = device,
                            status = status(device),
                            hasPendingLog = hasPendingLog(device.mac),
                            onTap = { connect(device.mac) },
                            onForget = { vm.forget(device.mac) },
                        )
                    }
                }

                item { SectionHeader("Nearby", color = Color.White) }
                when {
                    !bluetoothAvailable -> item {
                        ScanNote(
                            "Bluetooth is turned off or unavailable — turn it on to scan, or use demo mode.",
                            icon = Icons.Filled.BluetoothDisabled,
                            tint = Palette.warning,
                        )
                    }
                    nearby.isEmpty() -> item {
                        ScanNote(if (isScanning) "Scanning…" else "Tap Scan to look for devices.")
                    }
                    else -> items(nearby, key = { "nearby-${it.identifier}" }) { device ->
                        val isConnecting = activeId == device.identifier && activeState == DeviceState.Connecting
                        ScanRow(
                            icon = Icons.Filled.WifiTethering,
                            iconTint = Palette.info,
                            title = device.name.ifEmpty { "MetaWear" },
                            subtitle = device.identifier,
                            trailing = {
                                DeviceStatusChip(
                                    if (isConnecting) DeviceConnectionStatus.Connecting
                                    else DeviceConnectionStatus.Available(device.rssi),
                                )
                            },
                            onTap = { connect(device.identifier) },
                        )
                    }
                }

                item { SectionHeader("Demo", color = Color.White) }
                item {
                    BrandCard(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        contentPadding = PaddingValues(0.dp),
                        verticalSpacing = 0.dp,
                    ) {
                        ListItem(
                            headlineContent = { Text("Demo mode") },
                            supportingContent = { Text("Synthetic sensors — no hardware needed") },
                            trailingContent = {
                                Switch(checked = demoMode, onCheckedChange = { vm.setDemoMode(it) })
                            },
                            colors = cardListItemColors(),
                        )
                        demoRows.forEach { demo ->
                            HorizontalDivider()
                            val isConnecting = activeId == demo.identifier && activeState == DeviceState.Connecting
                            ListItem(
                                headlineContent = { Text(demo.name) },
                                supportingContent = { Text("Tap to connect to the simulated board") },
                                leadingContent = {
                                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = Palette.accent)
                                },
                                trailingContent = {
                                    if (isConnecting) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    } else {
                                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                                    }
                                },
                                colors = cardListItemColors(),
                                modifier = Modifier.combinedClickable(onClick = { connect(demo.identifier) }),
                            )
                        }
                    }
                }

                item {
                    BrandCard(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        contentPadding = PaddingValues(0.dp),
                        onClick = onSessionHistory,
                    ) {
                        ListItem(
                            headlineContent = { Text("Session History") },
                            supportingContent = { Text("Saved streams and downloaded logs") },
                            leadingContent = {
                                Icon(Icons.Filled.History, contentDescription = null, tint = Palette.accent)
                            },
                            trailingContent = {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                            },
                            colors = cardListItemColors(),
                        )
                    }
                }
            }
        }
    }
}

/** Short explanatory line on the orange field, in white. */
@Composable
private fun ScanNote(text: String, icon: ImageVector? = null, tint: Color = Color.White) {
    Row(
        modifier = Modifier.padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.9f))
    }
}

/** One device row on a light card: icon, name, identifier, trailing status. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScanRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String?,
    trailing: @Composable () -> Unit,
    onTap: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    titleBadge: (@Composable () -> Unit)? = null,
    dimmed: Boolean = false,
) {
    BrandCard(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = if (dimmed) 0.75f else 1f),
        contentPadding = PaddingValues(0.dp),
    ) {
        ListItem(
            headlineContent = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title)
                    titleBadge?.invoke()
                }
            },
            supportingContent = subtitle?.let {
                { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            },
            leadingContent = { Icon(icon, contentDescription = null, tint = iconTint) },
            trailingContent = trailing,
            colors = cardListItemColors(),
            modifier = Modifier.combinedClickable(onClick = onTap, onLongClick = onLongPress),
        )
    }
}

@Composable
private fun RememberedDeviceRow(
    device: RememberedDevice,
    status: DeviceConnectionStatus,
    hasPendingLog: Boolean,
    onTap: () -> Unit,
    onForget: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        ScanRow(
            icon = Icons.Filled.Sensors,
            iconTint = Palette.accent,
            title = device.name.ifEmpty { "MetaWear" },
            subtitle = device.mac,
            trailing = { DeviceStatusChip(status) },
            onTap = onTap,
            onLongPress = { menuOpen = true },
            dimmed = status == DeviceConnectionStatus.Offline,
            titleBadge = if (hasPendingLog) {
                {
                    // Red, not amber: the board is actively recording — the
                    // same colour the state chip uses for "Logging".
                    Icon(
                        Icons.Filled.FiberManualRecord,
                        contentDescription = "Logging session waiting",
                        tint = Palette.danger,
                        modifier = Modifier.size(12.dp),
                    )
                }
            } else {
                null
            },
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Forget device") },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onForget()
                },
            )
        }
    }
}
