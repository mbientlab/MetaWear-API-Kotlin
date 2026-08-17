package com.mbientlab.metawear.app.ui.stream

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.mbientlab.metawear.app.core.BandwidthAdvisor
import com.mbientlab.metawear.app.core.SensorKey
import com.mbientlab.metawear.app.core.SensorSelection
import com.mbientlab.metawear.app.ui.components.BandwidthCard
import com.mbientlab.metawear.app.ui.components.BrandCard
import com.mbientlab.metawear.app.ui.components.formatHz
import com.mbientlab.metawear.app.ui.components.icon
import com.mbientlab.metawear.app.ui.theme.Palette
import com.mbientlab.metawear.app.ui.theme.forText
import com.mbientlab.metawear.model.ModuleInfo
import com.mbientlab.metawear.protocol.Module

/** Sensors the connected board can actually run (module-presence gating). */
fun availableSensors(modules: Map<Module, ModuleInfo>): List<SensorKey> {
    fun has(module: Module) = modules[module]?.isPresent ?: false
    // Before connect (empty table) everything shows; after connect gate on
    // hardware presence reported by module discovery.
    if (modules.isEmpty()) return SensorKey.entries
    return SensorKey.entries.filter { key ->
        when (key) {
            SensorKey.ACCELEROMETER -> has(Module.ACCELEROMETER)
            SensorKey.GYROSCOPE -> has(Module.GYRO)
            SensorKey.MAGNETOMETER -> has(Module.MAGNETOMETER)
            SensorKey.TEMPERATURE -> has(Module.TEMPERATURE)
            SensorKey.HUMIDITY -> has(Module.HUMIDITY)
            SensorKey.PRESSURE, SensorKey.PRESSURE_STREAMED, SensorKey.ALTITUDE -> has(Module.BAROMETER)
            SensorKey.AMBIENT_LIGHT -> has(Module.AMBIENT_LIGHT)
            else -> has(Module.SENSOR_FUSION)
        }
    }
}

/**
 * Multi-select sensor picker with per-sensor rate/range chips (streamed) or
 * polling-interval chips (polled readables), plus the bandwidth advisory.
 * Shared by the live-stream, logging, and group-logging screens.
 *
 * @param loggingMode hides stream-only signals (altitude) that can't be
 *   captured to flash.
 * @param locked disables every control — used while a log session is
 *   recording so the configuration can't be edited mid-session.
 */
@Composable
fun SensorConfigSection(
    modules: Map<Module, ModuleInfo>,
    selections: List<SensorSelection>,
    onSelectionsChange: (List<SensorSelection>) -> Unit,
    loggingMode: Boolean = false,
    /** Kinds hidden outright (e.g. group logging excludes temperature/humidity). */
    excludeKeys: Set<SensorKey> = emptySet(),
    locked: Boolean = false,
) {
    val available = availableSensors(modules)
        .filter { !loggingMode || it.canLog }
        .filterNot { it in excludeKeys }
    val groups = listOf(
        "Motion" to available.filter { !it.isFusion && !it.isEnvironmental },
        "Fusion" to available.filter { it.isFusion },
        "Environmental" to available.filter { it.isEnvironmental },
    ).filter { it.second.isNotEmpty() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        groups.forEach { (title, keys) ->
            CollapsibleSensorGroup(
                title = title,
                keys = keys,
                selections = selections,
                onSelectionsChange = onSelectionsChange,
                locked = locked,
            )
        }

        BandwidthCard(
            aggregateHz = BandwidthAdvisor.aggregateHz(selections),
            onHalve = { onSelectionsChange(BandwidthAdvisor.halved(selections)) },
        )
    }
}

/**
 * One sensor group behind a tappable header. Starts expanded only when one
 * of its sensors is already selected, so a screen with a single active
 * sensor shows one open group and two folded ones — and the content below
 * the picker (active loggers, download) stays within reach. A collapsed
 * group still reports how many of its sensors are selected.
 */
@Composable
private fun CollapsibleSensorGroup(
    title: String,
    keys: List<SensorKey>,
    selections: List<SensorSelection>,
    onSelectionsChange: (List<SensorSelection>) -> Unit,
    locked: Boolean,
) {
    val selectedCount = keys.count { key -> selections.any { it.key == key } }
    var expanded by rememberSaveable(title) { mutableStateOf(selectedCount > 0) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable { expanded = !expanded }
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = Palette.accent.forText(),
                modifier = Modifier.weight(1f),
            )
            if (selectedCount > 0) {
                Text(
                    "$selectedCount selected",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                keys.forEach { key ->
                    SensorCard(key, selections, onSelectionsChange, locked)
                }
            }
        }
    }
}

@Composable
private fun SensorCard(
    key: SensorKey,
    selections: List<SensorSelection>,
    onSelectionsChange: (List<SensorSelection>) -> Unit,
    locked: Boolean,
) {
    val selection = selections.firstOrNull { it.key == key }
    val onToggle: (Boolean) -> Unit = { checked ->
        onSelectionsChange(
            if (checked) selections + SensorSelection(key)
            else selections.filterNot { it.key == key },
        )
    }
    BrandCard(onClick = if (locked) null else ({ onToggle(selection == null) })) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                key.icon,
                contentDescription = null,
                tint = if (selection != null) Palette.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Text(key.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Checkbox(
                checked = selection != null,
                onCheckedChange = onToggle,
                enabled = !locked,
            )
        }
        if (selection != null) {
            fun update(updated: SensorSelection) =
                onSelectionsChange(selections.map { if (it.key == key) updated else it })

            if (key.isPolled) {
                IntervalRow(selection, ::update, locked)
            } else {
                RateRow(selection, ::update, locked)
                if (key.rangeOptions.isNotEmpty()) {
                    RangeRow(selection, ::update, locked)
                }
            }
        }
    }
}

@Composable
private fun RateRow(selection: SensorSelection, onChange: (SensorSelection) -> Unit, locked: Boolean) {
    ChipRow("Rate") {
        selection.key.rateOptionsHz.forEach { hz ->
            FilterChip(
                selected = selection.hz == hz,
                onClick = { onChange(selection.copy(hz = hz)) },
                label = { Text(formatHz(hz)) },
                enabled = !locked,
            )
        }
    }
}

@Composable
private fun RangeRow(selection: SensorSelection, onChange: (SensorSelection) -> Unit, locked: Boolean) {
    ChipRow("Range") {
        selection.key.rangeOptions.forEach { range ->
            FilterChip(
                selected = selection.range == range,
                onClick = { onChange(selection.copy(range = range)) },
                label = { Text("±${range.toInt()} ${selection.key.rangeUnit}") },
                enabled = !locked,
            )
        }
    }
}

/** Polling-interval chips (1 s … 5 m) for the environmental readables. */
@Composable
private fun IntervalRow(selection: SensorSelection, onChange: (SensorSelection) -> Unit, locked: Boolean) {
    ChipRow("Every") {
        selection.key.pollIntervalOptionsMs.forEach { intervalMs ->
            FilterChip(
                selected = selection.effectivePollIntervalMs == intervalMs,
                onClick = { onChange(selection.withPollInterval(intervalMs)) },
                label = { Text(SensorSelection.formatPollInterval(intervalMs)) },
                enabled = !locked,
            )
        }
    }
}

@Composable
private fun ChipRow(label: String, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}
