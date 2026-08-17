package com.mbientlab.metawear.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.ui.graphics.vector.ImageVector
import com.mbientlab.metawear.app.core.SensorKey

/** One Material icon per sensor family — shared by pickers, chart cards, and history rows. */
val SensorKey.icon: ImageVector
    get() = when (this) {
        SensorKey.ACCELEROMETER -> Icons.Filled.OpenWith
        SensorKey.GYROSCOPE -> Icons.Filled.Cached
        SensorKey.MAGNETOMETER -> Icons.Filled.Explore
        SensorKey.TEMPERATURE -> Icons.Filled.DeviceThermostat
        SensorKey.HUMIDITY -> Icons.Filled.WaterDrop
        SensorKey.PRESSURE, SensorKey.PRESSURE_STREAMED -> Icons.Filled.Speed
        SensorKey.ALTITUDE -> Icons.Filled.Terrain
        SensorKey.AMBIENT_LIGHT -> Icons.Filled.WbSunny
        else -> Icons.Filled.ViewInAr // sensor-fusion outputs
    }

/**
 * Icon for a saved session, recovered from its capture-time label
 * ("Gyroscope · ±2000 dps · 25 Hz" → gyroscope). Falls back to a generic
 * record glyph for labels that don't map (foreign recoveries, legacy rows).
 */
fun sensorIconForLabel(label: String?): ImageVector {
    val head = label?.substringBefore(" · ") ?: return Icons.Filled.RadioButtonChecked
    return SensorKey.entries.firstOrNull { it.title == head }?.icon ?: Icons.Filled.RadioButtonChecked
}
