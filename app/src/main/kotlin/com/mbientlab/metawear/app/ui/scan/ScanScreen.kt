package com.mbientlab.metawear.app.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mbientlab.metawear.app.data.RememberedDevice
import com.mbientlab.metawear.app.ui.appViewModel
import com.mbientlab.metawear.app.ui.components.ErrorBanner
import com.mbientlab.metawear.app.ui.components.GlassCard
import com.mbientlab.metawear.app.ui.components.RssiPill
import com.mbientlab.metawear.app.ui.components.SectionHeader
import com.mbientlab.metawear.app.ui.theme.GlassTextDim
import com.mbientlab.metawear.app.ui.theme.GlassWarn
import com.mbientlab.metawear.app.vm.ScannerViewModel

/** Runtime permissions needed to scan/connect on this API level. */
private fun requiredBlePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

@Composable
fun ScanScreen(onDeviceSelected: () -> Unit, onGroupLogging: () -> Unit = {}) {
    val vm = appViewModel(::ScannerViewModel)
    val context = LocalContext.current

    val isScanning by vm.isScanning.collectAsState()
    val nearby by vm.nearbyDevices.collectAsState()
    val remembered by vm.remembered.collectAsState()
    val demoMode by vm.demoModeEnabled.collectAsState()
    val lastError by vm.lastError.collectAsState()

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

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("MetaWear", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Scan for nearby MetaMotion boards or run in demo mode.",
                style = MaterialTheme.typography.bodyMedium,
                color = GlassTextDim,
            )
        }

        item { ErrorBanner(lastError) { vm.clearError() } }

        item {
            GlassCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Demo mode", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Simulated MetaMotion S — no hardware needed",
                            style = MaterialTheme.typography.bodySmall,
                            color = GlassTextDim,
                        )
                    }
                    Switch(checked = demoMode, onCheckedChange = { vm.setDemoMode(it) })
                }
                if (!vm.isBluetoothAvailable() && !demoMode) {
                    Text(
                        "Bluetooth is off or unavailable on this device — enable demo mode to explore the app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = GlassWarn,
                    )
                }
            }
        }

        item {
            Button(
                onClick = {
                    if (isScanning) {
                        vm.stopScan()
                    } else if (hasPermissions()) {
                        permissionsGranted = true
                        vm.startScan()
                    } else {
                        permissionLauncher.launch(requiredBlePermissions())
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isScanning) "Stop Scanning" else "Start Scanning")
            }
        }

        item {
            TextButton(onClick = onGroupLogging, modifier = Modifier.fillMaxWidth()) {
                Text("Group Logging — record several boards at once")
            }
        }

        if (nearby.isNotEmpty()) {
            item { SectionHeader("Nearby") }
            items(nearby, key = { it.identifier }) { device ->
                GlassCard(
                    modifier = Modifier.clickable {
                        vm.stopScan()
                        vm.select(device.identifier)
                        onDeviceSelected()
                    },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(device.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (device.isDemo) "Demo device" else device.identifier,
                                style = MaterialTheme.typography.bodySmall,
                                color = GlassTextDim,
                            )
                        }
                        RssiPill(device.rssi)
                    }
                }
            }
        }

        if (remembered.isNotEmpty()) {
            item { SectionHeader("My Devices") }
            items(remembered, key = { it.mac }) { device ->
                RememberedRow(
                    device = device,
                    onConnect = {
                        vm.stopScan()
                        vm.select(device.mac)
                        onDeviceSelected()
                    },
                    onForget = { vm.forget(device.mac) },
                )
            }
        }
    }
}

@Composable
private fun RememberedRow(
    device: RememberedDevice,
    onConnect: () -> Unit,
    onForget: () -> Unit,
) {
    GlassCard(modifier = Modifier.clickable(onClick = onConnect)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleSmall)
                Text(device.mac, style = MaterialTheme.typography.bodySmall, color = GlassTextDim)
            }
            TextButton(onClick = onForget) { Text("Forget") }
        }
    }
}
