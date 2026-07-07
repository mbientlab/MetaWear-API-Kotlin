package com.mbientlab.metawear.app.ui.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mbientlab.metawear.app.ui.appViewModel
import com.mbientlab.metawear.app.ui.components.GlassCard
import com.mbientlab.metawear.app.ui.components.LabeledValue
import com.mbientlab.metawear.app.ui.components.SectionHeader
import com.mbientlab.metawear.app.vm.DeviceViewModel

@Composable
fun DeviceInfoScreen() {
    val vm = appViewModel(::DeviceViewModel)
    val info by vm.deviceInfo.collectAsState()
    val battery by vm.battery.collectAsState()
    val mac by vm.macAddress.collectAsState()
    val modules by vm.modules.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text("Device Info", style = MaterialTheme.typography.headlineSmall) }

        item {
            GlassCard {
                LabeledValue("Manufacturer", info?.manufacturer ?: "—")
                LabeledValue("Model", info?.model?.displayName ?: "—")
                LabeledValue("Model number", info?.modelNumber ?: "—")
                LabeledValue("Serial", info?.serialNumber ?: "—")
                LabeledValue("Firmware", info?.firmwareRevision ?: "—")
                LabeledValue("Hardware", info?.hardwareRevision ?: "—")
                LabeledValue("MAC", mac ?: vm.device?.identifier ?: "—")
                LabeledValue("Battery", battery?.let { "${it.charge}% · ${it.voltage} mV" } ?: "—")
            }
        }

        item { SectionHeader("Modules") }
        item {
            GlassCard {
                val present = modules.values.filter { it.isPresent }.sortedBy { it.module.value }
                if (present.isEmpty()) {
                    Text("No module table — connect first.", style = MaterialTheme.typography.bodySmall)
                } else {
                    present.forEach { module ->
                        LabeledValue(
                            module.module.name.lowercase().replaceFirstChar { it.uppercase() },
                            "impl ${module.implementation} · rev ${module.revision}",
                        )
                    }
                }
            }
        }
    }
}
