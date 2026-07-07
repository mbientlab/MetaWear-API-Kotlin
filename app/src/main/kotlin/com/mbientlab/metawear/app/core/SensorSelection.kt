package com.mbientlab.metawear.app.core

/**
 * The sensors the app can stream/log, with per-sensor rate/range menus and
 * display metadata. Port of `SensorSelection.swift` (lean: the environmental
 * sensors — barometer, temperature, humidity, ambient light — are not ported;
 * this app covers the IMU + fusion surface).
 */
enum class SensorKey(
    /** Human-readable name. */
    val title: String,
    /** Short tag used in export filenames (e.g. "accel"). */
    val shortTag: String,
) {
    ACCELEROMETER("Accelerometer", "accel"),
    GYROSCOPE("Gyroscope", "gyro"),
    MAGNETOMETER("Magnetometer", "mag"),
    FUSION_QUATERNION("Quaternion", "fusion-quat"),
    FUSION_EULER("Euler Angles", "fusion-euler"),
    FUSION_GRAVITY("Gravity", "fusion-gravity"),
    FUSION_LINEAR_ACCELERATION("Linear Acceleration", "fusion-linear"),
    FUSION_CORRECTED_ACC("Corrected Accel", "fusion-cacc"),
    FUSION_CORRECTED_GYRO("Corrected Gyro", "fusion-cgyro"),
    FUSION_CORRECTED_MAG("Corrected Mag", "fusion-cmag");

    /** Whether this output rides the on-board sensor-fusion engine. */
    val isFusion: Boolean get() = ordinal >= FUSION_QUATERNION.ordinal

    /** Sample-rate menu in Hz. Fusion outputs run at the engine's fixed 100 Hz. */
    val rateOptionsHz: List<Double>
        get() = when (this) {
            ACCELEROMETER, GYROSCOPE -> listOf(12.5, 25.0, 50.0, 100.0, 200.0)
            MAGNETOMETER -> listOf(10.0, 15.0, 20.0, 25.0, 30.0)
            else -> listOf(50.0, 100.0)
        }

    val defaultHz: Double
        get() = when (this) {
            ACCELEROMETER, GYROSCOPE -> 50.0
            MAGNETOMETER -> 25.0
            else -> 100.0
        }

    /** Full-scale range menu (g for accel, dps for gyro; empty otherwise). */
    val rangeOptions: List<Float>
        get() = when (this) {
            ACCELEROMETER -> listOf(2f, 4f, 8f, 16f)
            GYROSCOPE -> listOf(125f, 250f, 500f, 1000f, 2000f)
            else -> emptyList()
        }

    val defaultRange: Float?
        get() = when (this) {
            ACCELEROMETER -> 8f
            GYROSCOPE -> 2000f
            else -> null
        }

    /** Unit suffix for range menus ("g" / "dps"). */
    val rangeUnit: String
        get() = when (this) {
            ACCELEROMETER -> "g"
            GYROSCOPE -> "dps"
            else -> ""
        }

    /** Chart/readout styling for this sensor. */
    val axisStyle: AxisStyle
        get() = when (this) {
            ACCELEROMETER -> AxisStyle("g", -2f..2f, listOf("x", "y", "z"), 3)
            GYROSCOPE -> AxisStyle("dps", -2000f..2000f, listOf("x", "y", "z"), 3)
            MAGNETOMETER -> AxisStyle("µT", null, listOf("x", "y", "z"), 3)
            FUSION_QUATERNION -> AxisStyle("", -1f..1f, listOf("w", "x", "y", "z"), 4)
            FUSION_EULER -> AxisStyle("°", -180f..180f, listOf("heading", "pitch", "roll", "yaw"), 4)
            FUSION_GRAVITY -> AxisStyle("g", -1f..1f, listOf("x", "y", "z"), 3)
            FUSION_LINEAR_ACCELERATION -> AxisStyle("g", -1f..1f, listOf("x", "y", "z"), 3)
            FUSION_CORRECTED_ACC -> AxisStyle("g", null, listOf("x", "y", "z", "accuracy"), 3)
            FUSION_CORRECTED_GYRO -> AxisStyle("dps", null, listOf("x", "y", "z", "accuracy"), 3)
            FUSION_CORRECTED_MAG -> AxisStyle("µT", null, listOf("x", "y", "z", "accuracy"), 3)
        }
}

/**
 * Chart/readout styling for a sensor kind.
 *
 * @property labels one entry per exported CSV column (may exceed [chartChannels]
 *   — corrected fusion outputs export an accuracy column that isn't plotted).
 */
data class AxisStyle(
    val unit: String,
    val yRange: ClosedFloatingPointRange<Float>?,
    val labels: List<String>,
    /** Number of channels drawn on the live chart. */
    val chartChannels: Int,
)

/** One configured sensor pick: which sensor, at what rate/range. */
data class SensorSelection(
    val key: SensorKey,
    val hz: Double = key.defaultHz,
    /** Full-scale range in the sensor's unit (g / dps); `null` when fixed. */
    val range: Float? = key.defaultRange,
) {
    /** User-facing label, e.g. "Accelerometer · ±8 g · 50 Hz". */
    val displayLabel: String
        get() = buildString {
            append(key.title)
            range?.let { append(" · ±${trim(it.toDouble())} ${key.rangeUnit}") }
            append(" · ${trim(hz)} Hz")
        }

    companion object {
        private fun trim(value: Double): String =
            if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
    }
}
