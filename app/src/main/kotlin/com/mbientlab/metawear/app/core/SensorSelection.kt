package com.mbientlab.metawear.app.core

/**
 * The sensors the app can stream/log, with per-sensor rate/range menus and
 * display metadata. Port of `SensorSelection.swift` (lean: ambient light is
 * not ported; this app covers the IMU + fusion surface plus the polled
 * environmental readables — temperature, humidity, barometer pressure).
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
    FUSION_CORRECTED_MAG("Corrected Mag", "fusion-cmag"),
    TEMPERATURE("Temperature", "temp"),
    HUMIDITY("Humidity", "humidity"),
    PRESSURE("Pressure", "pressure");

    /** Whether this output rides the on-board sensor-fusion engine. */
    val isFusion: Boolean
        get() = when (this) {
            FUSION_QUATERNION, FUSION_EULER, FUSION_GRAVITY, FUSION_LINEAR_ACCELERATION,
            FUSION_CORRECTED_ACC, FUSION_CORRECTED_GYRO, FUSION_CORRECTED_MAG,
            -> true
            else -> false
        }

    /**
     * Whether this is a polled environmental readable — captured via
     * timer-driven one-shot reads (`PolledLogger` for flash logging,
     * `device.poll` for the live tile) rather than a streamed data interrupt.
     */
    val isPolled: Boolean
        get() = this == TEMPERATURE || this == HUMIDITY || this == PRESSURE

    /** Sample-rate menu in Hz. Fusion outputs run at the engine's fixed 100 Hz. */
    val rateOptionsHz: List<Double>
        get() = when (this) {
            ACCELEROMETER, GYROSCOPE -> listOf(12.5, 25.0, 50.0, 100.0, 200.0)
            MAGNETOMETER -> listOf(10.0, 15.0, 20.0, 25.0, 30.0)
            TEMPERATURE, HUMIDITY, PRESSURE -> emptyList()   // interval-picked instead
            else -> listOf(50.0, 100.0)
        }

    /** Polling-interval menu (ms) for polled readables; empty for streamed. */
    val pollIntervalOptionsMs: List<Long>
        get() = if (isPolled) listOf(1_000L, 10_000L, 30_000L, 60_000L, 300_000L) else emptyList()

    val defaultPollIntervalMs: Long? get() = if (isPolled) 1_000L else null

    val defaultHz: Double
        get() = when (this) {
            ACCELEROMETER, GYROSCOPE -> 50.0
            MAGNETOMETER -> 25.0
            TEMPERATURE, HUMIDITY, PRESSURE -> 1.0   // 1 s default interval
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
            TEMPERATURE -> AxisStyle("°C", null, listOf("value"), 1)
            HUMIDITY -> AxisStyle("%", 0f..100f, listOf("value"), 1)
            PRESSURE -> AxisStyle("Pa", null, listOf("value"), 1)
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

/** One configured sensor pick: which sensor, at what rate/range or interval. */
data class SensorSelection(
    val key: SensorKey,
    val hz: Double = key.defaultHz,
    /** Full-scale range in the sensor's unit (g / dps); `null` when fixed. */
    val range: Float? = key.defaultRange,
    /** Polling interval for polled readables; `null` for streamed sensors. */
    val pollIntervalMs: Long? = key.defaultPollIntervalMs,
) {
    /**
     * User-facing label, e.g. "Accelerometer · ±8 g · 50 Hz" or
     * "Temperature · every 10 s".
     */
    val displayLabel: String
        get() = buildString {
            append(key.title)
            if (key.isPolled) {
                append(" · every ${formatPollInterval(effectivePollIntervalMs)}")
            } else {
                range?.let { append(" · ±${trim(it.toDouble())} ${key.rangeUnit}") }
                append(" · ${trim(hz)} Hz")
            }
        }

    /** Poll interval with the per-key default as fallback. */
    val effectivePollIntervalMs: Long
        get() = pollIntervalMs ?: key.defaultPollIntervalMs ?: 1_000L

    /**
     * Samples per second this selection produces — the streamed rate, or the
     * poll-interval reciprocal (drives display-stride and bandwidth math).
     */
    val samplesPerSecond: Double
        get() = if (key.isPolled) 1000.0 / effectivePollIntervalMs else hz

    /** Returns a copy configured for the given polling interval. */
    fun withPollInterval(intervalMs: Long): SensorSelection =
        copy(pollIntervalMs = intervalMs, hz = 1000.0 / intervalMs)

    companion object {
        private fun trim(value: Double): String =
            if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

        /** "1 s" / "30 s" / "1 m" / "5 m" — whole minutes collapse to m. */
        fun formatPollInterval(ms: Long): String {
            val seconds = ms / 1000.0
            return when {
                seconds >= 60.0 && seconds % 60.0 == 0.0 -> "${(seconds / 60).toLong()} m"
                seconds == seconds.toLong().toDouble() -> "${seconds.toLong()} s"
                else -> "$seconds s"
            }
        }
    }
}
