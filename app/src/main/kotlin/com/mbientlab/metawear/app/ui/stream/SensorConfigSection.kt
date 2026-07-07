package com.mbientlab.metawear.app.ui.stream

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mbientlab.metawear.app.core.BandwidthAdvisor
import com.mbientlab.metawear.app.core.SensorKey
import com.mbientlab.metawear.app.core.SensorSelection
import com.mbientlab.metawear.app.ui.components.GlassCard
import com.mbientlab.metawear.app.ui.theme.GlassTextDim
import com.mbientlab.metawear.app.ui.theme.GlassWarn
import com.mbientlab.metawear.model.ModuleInfo
import com.mbientlab.metawear.protocol.Module

/** Sensors the connected board can actually run (module-presence gating). */
fun availableSensors(modules: Map<Module, ModuleInfo>): List<SensorKey> {
    fun has(module: Module) = modules[module]?.isPresent ?: false
    // Before connect (empty table) everything shows; after connect gate on
    // hardware presence like the Swift SensorPickerSection.
    if (modules.isEmpty()) return SensorKey.entries
    return SensorKey.entries.filter { key ->
        when (key) {
            SensorKey.ACCELEROMETER -> has(Module.ACCELEROMETER)
            SensorKey.GYROSCOPE -> has(Module.GYRO)
            SensorKey.MAGNETOMETER -> has(Module.MAGNETOMETER)
            else -> has(Module.SENSOR_FUSION)
        }
    }
}

/**
 * Multi-select sensor picker with per-sensor rate/range chips and the
 * bandwidth advisory. Shared by the live-stream and logging screens (port of
 * `SensorConfigView` + `SensorPickerSection`).
 */
@Composable
fun SensorConfigSection(
    modules: Map<Module, ModuleInfo>,
    selections: List<SensorSelection>,
    onSelectionsChange: (List<SensorSelection>) -> Unit,
) {
    val available = availableSensors(modules)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        available.forEach { key ->
            val selection = selections.firstOrNull { it.key == key }
            GlassCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(key.title, style = MaterialTheme.typography.titleSmall)
                    Checkbox(
                        checked = selection != null,
                        onCheckedChange = { checked ->
                            onSelectionsChange(
                                if (checked) selections + SensorSelection(key)
                                else selections.filterNot { it.key == key },
                            )
                        },
                    )
                }
                if (selection != null) {
                    RateRow(selection) { updated ->
                        onSelectionsChange(selections.map { if (it.key == key) updated else it })
                    }
                    if (key.rangeOptions.isNotEmpty()) {
                        RangeRow(selection) { updated ->
                            onSelectionsChange(selections.map { if (it.key == key) updated else it })
                        }
                    }
                }
            }
        }

        if (BandwidthAdvisor.isOverCeiling(selections)) {
            GlassCard {
                Text(
                    "Combined rate ${BandwidthAdvisor.aggregateHz(selections).toInt()} Hz exceeds the " +
                        "~${BandwidthAdvisor.BLE_SAFE_CEILING_HZ.toInt()} Hz BLE ceiling — samples may drop.",
                    style = MaterialTheme.typography.bodySmall,
                    color = GlassWarn,
                )
                TextButton(onClick = { onSelectionsChange(BandwidthAdvisor.halved(selections)) }) {
                    Text("Halve all rates")
                }
            }
        }
    }
}

@Composable
private fun RateRow(selection: SensorSelection, onChange: (SensorSelection) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Rate", style = MaterialTheme.typography.labelMedium, color = GlassTextDim)
        selection.key.rateOptionsHz.forEach { hz ->
            FilterChip(
                selected = selection.hz == hz,
                onClick = { onChange(selection.copy(hz = hz)) },
                label = { Text(formatHz(hz)) },
            )
        }
    }
}

@Composable
private fun RangeRow(selection: SensorSelection, onChange: (SensorSelection) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Range", style = MaterialTheme.typography.labelMedium, color = GlassTextDim)
        selection.key.rangeOptions.forEach { range ->
            FilterChip(
                selected = selection.range == range,
                onClick = { onChange(selection.copy(range = range)) },
                label = { Text("±${range.toInt()} ${selection.key.rangeUnit}") },
            )
        }
    }
}

private fun formatHz(hz: Double): String =
    if (hz == hz.toLong().toDouble()) "${hz.toLong()} Hz" else "$hz Hz"
