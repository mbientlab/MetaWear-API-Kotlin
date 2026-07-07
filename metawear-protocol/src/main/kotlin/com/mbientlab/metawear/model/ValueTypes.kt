package com.mbientlab.metawear.model

import com.mbientlab.metawear.protocol.Module
import kotlinx.datetime.Instant

// The pure value types produced by the SDK.

/**
 * A value paired with the host wall-clock time it was received at.
 *
 * The timestamp is taken by the SDK at packet-arrival time on the host — it is
 * not a hardware timestamp. For high-rate streams the inter-sample interval is
 * more accurate than the absolute time. For logged samples (which carry a
 * device tick timestamp) see [LoggedSample].
 */
data class Timestamped<out T>(val time: Instant, val value: T)

/** A typed sensor sample recovered from the on-device flash log. */
data class LoggedSample<out T>(
    /** Wall-clock timestamp, derived from the device's time reference when available. */
    val date: Instant,
    /** Elapsed milliseconds since the MetaWear last reset (raw tick time). */
    val tickMs: Double,
    val value: T,
)

/** A progress update yielded during a long-running log download. */
data class Download<out T>(
    /** All samples decoded so far. Each yield is cumulative. */
    val data: T,
    /** `0.0`..`1.0`, monotonically non-decreasing. */
    val percentComplete: Double,
    /** Total raw log entries reported at the start of this readout. */
    val totalEntries: Long? = null,
    /** Number of raw entries received so far on the wire. */
    val entriesDownloaded: Long? = null,
)

/**
 * A single logger subscription active on the MetaWear, as returned by
 * `MetaWearDevice.queryActiveLoggers()`.
 */
data class ActiveLogger(
    /** The firmware-assigned logger ID (0x00..0x1F). */
    val loggerID: Int,
    val module: Module,
    val register: Int,
    /**
     * Raw channel byte (response[4]). For per-channel sources (e.g. multi-
     * thermistor temperature) this is the channel index. For packed IMU
     * sources this is 0xFF.
     */
    val channel: Int,
    /**
     * Byte offset of this chunk within the parent signal's payload. Low 5 bits
     * of the packed byte.
     */
    val chunkOffset: Int,
    /** Byte length of this chunk. `((packed >> 5) & 0x7) + 1`. */
    val chunkLength: Int,
)

/**
 * A single data processor on-device, as returned by
 * `MetaWearDevice.queryActiveProcessors()`. Used to reconstruct the processor
 * graph behind an anonymous signal.
 */
data class ActiveProcessor(
    /** The firmware-assigned processor ID (0x00..0x1F). */
    val processorID: Int,
    /**
     * The module that feeds this processor. When equal to
     * [Module.DATA_PROCESSOR], the parent is another processor and
     * [parentProcessorID] is meaningful.
     */
    val parentModule: Module,
    /**
     * Parent register. For a sensor root this is the data-register; for a
     * processor chain it's the NOTIFY register (0x03).
     */
    val parentRegister: Int,
    /**
     * When [parentModule] is [Module.DATA_PROCESSOR], this is the parent
     * processor's ID. Otherwise it's the raw offset/channel byte from the
     * response (commonly 0xFF).
     */
    val parentProcessorID: Int,
    /** Byte offset into the parent's output data. */
    val chunkOffset: Int,
    /** Byte length of the processor's input chunk within the parent's output. */
    val chunkLength: Int,
    /**
     * Processor type code (see the `MWProcessorType` table in the C++ SDK).
     * Examples: 0x02 = accumulate/count, 0x07 = RMS/RSS, 0x08 = time, 0x1B = fuser.
     */
    val processorType: Int,
    /** Processor-specific config bytes, stripped of the response header (0..255 each). */
    val configBytes: List<Int> = emptyList(),
) {
    /**
     * True when this processor reads its input from another processor (as
     * opposed to a root sensor signal).
     */
    val parentIsProcessor: Boolean
        get() = parentModule == Module.DATA_PROCESSOR && parentRegister == 0x03
}

/**
 * A 3-axis floating-point vector in the sensor's local frame.
 *
 * Units depend on the producing sensor: accelerometer `g`, gyroscope dps,
 * magnetometer µT, sensor-fusion gravity / linear acceleration `g`.
 */
data class CartesianFloat(val x: Float, val y: Float, val z: Float)

/** A unit quaternion (Hamilton convention, w first) from the fusion algorithm. */
data class Quaternion(val w: Float, val x: Float, val y: Float, val z: Float)

/** Orientation as Euler angles (degrees) from the fusion algorithm. */
data class EulerAngles(val heading: Float, val pitch: Float, val roll: Float, val yaw: Float)

/**
 * A 3-axis vector paired with the fusion algorithm's calibration accuracy.
 * `accuracy`: 0 = uncalibrated, 1 = low, 2 = medium, 3 = high.
 */
data class CorrectedCartesianFloat(val x: Float, val y: Float, val z: Float, val accuracy: Int)

/** The board's battery state. */
data class BatteryState(
    /** Battery voltage in millivolts (a full LiPo reads ~4200 mV). */
    val voltage: Int,
    /** Remaining capacity as a percentage, 0–100. */
    val charge: Int,
)

/**
 * A bidirectional Hz ↔ ms value type for sensor output data rates.
 * Prefer the named constants ([HZ100], etc.) where they exist.
 */
class Frequency(val hz: Double) {
    val periodMs: Double get() = 1000.0 / hz

    override fun toString(): String = "$hz Hz"
    override fun equals(other: Any?): Boolean = other is Frequency && other.hz == hz
    override fun hashCode(): Int = hz.hashCode()

    companion object {
        fun fromPeriodMs(periodMs: Double): Frequency = Frequency(1000.0 / periodMs)

        val HZ12_5 = Frequency(12.5)
        val HZ25 = Frequency(25.0)
        val HZ50 = Frequency(50.0)
        val HZ100 = Frequency(100.0)
        val HZ200 = Frequency(200.0)
        val HZ400 = Frequency(400.0)
        val HZ800 = Frequency(800.0)
        val HZ1600 = Frequency(1600.0)
    }
}

/**
 * The board's identity, read from the standard BLE Device Information service
 * (`0x180A`) during connect.
 */
data class DeviceInformation(
    val manufacturer: String,
    val modelNumber: String,
    val serialNumber: String,
    val firmwareRevision: String,
    val hardwareRevision: String,
) {
    /** The board model derived from [modelNumber]. */
    val model: BoardModel get() = BoardModel.fromModelNumber(modelNumber)

    /** Whether [hardwareRevision] is a documented revision for the detected [model]. */
    val isHardwareRevisionSupported: Boolean
        get() = model.isHardwareRevisionSupported(hardwareRevision)
}

/**
 * One row from the board's module-discovery handshake (response to
 * `[module_id, 0x80]`). `extra` holds bytes following `[module, 0x80, impl, rev]`
 * as unsigned values 0..255.
 */
data class ModuleInfo(
    val module: Module,
    val implementation: Int,
    val revision: Int,
    val extra: List<Int> = emptyList(),
) {
    /** `true` when the firmware reports this module exists (implementation != 0xFF). */
    val isPresent: Boolean get() = implementation != 0xFF
}
