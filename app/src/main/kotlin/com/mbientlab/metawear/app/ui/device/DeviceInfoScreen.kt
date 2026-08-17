package com.mbientlab.metawear.app.ui.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mbientlab.metawear.app.ui.appViewModel
import com.mbientlab.metawear.app.ui.components.AppScaffold
import com.mbientlab.metawear.app.ui.components.GroupCard
import com.mbientlab.metawear.app.ui.components.LabeledValue
import com.mbientlab.metawear.app.ui.components.SectionHeader
import com.mbientlab.metawear.app.ui.components.cardListItemColors
import com.mbientlab.metawear.app.vm.DeviceViewModel
import com.mbientlab.metawear.model.ModuleInfo
import com.mbientlab.metawear.protocol.Module

/**
 * Identity rows from the Device Information service, then every module the
 * SDK knows about — present ones with their implementation/revision bytes,
 * absent ones kept in the list as "—" so what's on this board is obvious.
 */
@Composable
fun DeviceInfoScreen(onBack: () -> Unit) {
    val vm = appViewModel(::DeviceViewModel)
    val info by vm.deviceInfo.collectAsState()
    val battery by vm.battery.collectAsState()
    val mac by vm.macAddress.collectAsState()
    val modules by vm.modules.collectAsState()

    AppScaffold(title = "Device Info", onBack = onBack) { padding ->
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
            item { SectionHeader("Identity") }
            item {
                GroupCard {
                    LabeledValue("Model", info?.modelNumber ?: "—")
                    HorizontalDivider()
                    LabeledValue("Manufacturer", info?.manufacturer ?: "—")
                    HorizontalDivider()
                    LabeledValue("Serial", info?.serialNumber ?: "—")
                    HorizontalDivider()
                    LabeledValue("Firmware", info?.firmwareRevision ?: "—")
                    HorizontalDivider()
                    LabeledValue("Hardware", info?.hardwareRevision ?: "—")
                    HorizontalDivider()
                    LabeledValue("MAC", mac ?: vm.device?.identifier ?: "—", monospace = true)
                    HorizontalDivider()
                    LabeledValue("Battery", battery?.let { "${it.charge}% · ${it.voltage} mV" } ?: "—")
                }
            }

            item { SectionHeader("Modules") }
            item {
                GroupCard {
                    Module.entries.forEachIndexed { index, module ->
                        if (index > 0) HorizontalDivider()
                        ModuleRow(module, modules[module])
                    }
                }
            }
        }
    }
}

/** One Modules row: name left; "impl 0xNN · rev N" or a muted "—" right. */
@Composable
private fun ModuleRow(module: Module, info: ModuleInfo?) {
    val name = module.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    ListItem(
        headlineContent = { Text(name) },
        trailingContent = {
            if (info != null && info.isPresent) {
                Text(
                    "impl ${hex(info.implementation)} · rev ${info.revision}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("—", color = MaterialTheme.colorScheme.outline)
            }
        },
        colors = cardListItemColors(),
    )
}

private fun hex(value: Int): String = "0x" + value.toString(16).uppercase().padStart(2, '0')
