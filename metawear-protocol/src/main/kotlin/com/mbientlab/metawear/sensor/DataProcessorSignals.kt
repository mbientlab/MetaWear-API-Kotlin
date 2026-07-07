package com.mbientlab.metawear.sensor

import com.mbientlab.metawear.protocol.Module

// The signal half of the data-processor surface — the `Signal` interface,
// the known input signals, and the processor handle returned by
// `createProcessor` (which is itself a signal, enabling processor chaining).

/**
 * Any data source that can feed a data processor: a sensor signal or a
 * processor's output.
 *
 * The [sourceConfigByte] encodes the total sample length and byte offset into
 * the single byte that the board expects at position 5 of every ADD command:
 * ```
 * source_config = ((n_channels * channel_size - 1) << 5) | offset
 * ```
 * (Verified against MetaWear-SDK-Cpp datasignal.cpp `get_data_ubyte()`.)
 */
interface Signal {
    /** MetaWear module ID. */
    val moduleId: Int

    /** Register ID on that module (not OR'd with 0x80). */
    val registerId: Int

    /** Data ID byte (0xFF means "any / no ID"). */
    val dataId: Int

    /** Number of data channels (axes) per sample. */
    val nChannels: Int

    /** Bytes per channel. */
    val channelSize: Int

    /** Byte offset within the sample (usually 0). */
    val offset: Int

    /** Whether the signal values are signed. */
    val isSigned: Boolean

    /** Total bytes per sample = nChannels × channelSize. */
    val dataLength: Int get() = nChannels * channelSize

    /** source_config byte for ADD commands, masked to a single byte (0..255). */
    val sourceConfigByte: Int get() = (((dataLength - 1) shl 5) or offset) and 0xFF
}

/**
 * Switch button state (1 byte, unsigned).
 *
 * Feed into a [DataProcessor.Counter] to count presses, or into a
 * [DataProcessor.Comparator] to react to specific press/release transitions.
 */
class SwitchSignal : Signal {
    override val moduleId: Int get() = Module.SWITCH.value
    override val registerId: Int get() = 0x01
    override val dataId: Int get() = 0xFF
    override val nChannels: Int get() = 1
    override val channelSize: Int get() = 1
    override val offset: Int get() = 0
    override val isSigned: Boolean get() = false
}

/**
 * Raw 3-axis accelerometer data (3 × Int16 = 6 bytes, signed).
 *
 * Use as the source of an [DataProcessor.Rms]/[DataProcessor.Rss] processor to
 * reduce to magnitude, then [DataProcessor.Threshold] or [DataProcessor.Pulse]
 * for activity detection.
 */
class AccelerometerSignal : Signal {
    override val moduleId: Int get() = Module.ACCELEROMETER.value
    override val registerId: Int get() = 0x04
    override val dataId: Int get() = 0xFF
    override val nChannels: Int get() = 3
    override val channelSize: Int get() = 2
    override val offset: Int get() = 0
    override val isSigned: Boolean get() = true
}

/**
 * Raw 3-axis gyroscope data (3 × Int16 = 6 bytes, signed).
 *
 * Pair with a [DataProcessor.Buffer] + [DataProcessor.Fuser] to bundle gyro
 * samples alongside accelerometer data in a single packet.
 */
class GyroscopeSignal : Signal {
    override val moduleId: Int get() = Module.GYRO.value
    override val registerId: Int get() = 0x05
    override val dataId: Int get() = 0xFF
    override val nChannels: Int get() = 3
    override val channelSize: Int get() = 2
    override val offset: Int get() = 0
    override val isSigned: Boolean get() = true
}

/**
 * GPIO analog ADC reading (1 × UInt16 = 2 bytes, unsigned).
 *
 * Wraps the analog input on a specific pin so it can be wired into a processor
 * (e.g. [DataProcessor.Pulse] for spike detection on an external sensor).
 */
class GpioAnalogSignal(pin: Int, mode: Mode = Mode.ADC) : Signal {

    /** Which analog read register to source from. */
    enum class Mode {
        /** Raw ADC counts (register 0x07). */
        ADC,

        /** Absolute voltage reference reading (register 0x06). */
        ABSOLUTE,
    }

    override val moduleId: Int get() = Module.GPIO.value
    override val registerId: Int = if (mode == Mode.ADC) 0x07 else 0x06
    override val dataId: Int = pin
    override val nChannels: Int get() = 1
    override val channelSize: Int get() = 2
    override val offset: Int get() = 0
    override val isSigned: Boolean get() = false
}

/**
 * Single temperature channel (1 × Int16 = 2 bytes, signed).
 *
 * Pick a specific thermistor / on-die source via the [dataId] channel index.
 * Often paired with a [DataProcessor.Comparator] to fire an event when
 * temperature crosses a setpoint.
 */
class TemperatureSignal(channel: Int = 0) : Signal {
    override val moduleId: Int get() = Module.TEMPERATURE.value
    override val registerId: Int get() = 0x01 or 0x80 or 0x40 // 0xC1 (read + data_id)
    override val dataId: Int = channel
    override val nChannels: Int get() = 1
    override val channelSize: Int get() = 2
    override val offset: Int get() = 0
    override val isSigned: Boolean get() = true
}

// ---- Sensor fusion signals ----
//
// Each fusion output (quaternion, euler, gravity, linear-accel) is exposed as
// both a Loggable (SensorFusion.kt) and as a Signal here. The signal form is
// what `createProcessor(config, source)` consumes — it carries just enough
// metadata for the board to wire the signal as a processor input. Sensor
// lifecycle (configure / enable / start / stop / disable) remains the caller's
// responsibility via the Loggable form.
//
// Wire layout per the MblMwSensorFusion register table:
//   0x07 QUATERNION   → 16 bytes (4 × float32)
//   0x08 EULER_ANGLES → 16 bytes (4 × float32)
//   0x09 GRAVITY      → 12 bytes (3 × float32)
//   0x0A LINEAR_ACC   → 12 bytes (3 × float32)
//
// All channelSize values are 4 (single float32 per axis).

/** Quaternion output of sensor fusion (4 × float32 = 16 bytes, signed). */
class SensorFusionQuaternionSignal : Signal {
    override val moduleId: Int get() = Module.SENSOR_FUSION.value
    override val registerId: Int get() = 0x07
    override val dataId: Int get() = 0xFF
    override val nChannels: Int get() = 4
    override val channelSize: Int get() = 4
    override val offset: Int get() = 0
    override val isSigned: Boolean get() = true
}

/** Euler-angles output of sensor fusion (4 × float32 = 16 bytes, signed). */
class SensorFusionEulerSignal : Signal {
    override val moduleId: Int get() = Module.SENSOR_FUSION.value
    override val registerId: Int get() = 0x08
    override val dataId: Int get() = 0xFF
    override val nChannels: Int get() = 4
    override val channelSize: Int get() = 4
    override val offset: Int get() = 0
    override val isSigned: Boolean get() = true
}

/** Gravity-vector output of sensor fusion (3 × float32 = 12 bytes, signed). */
class SensorFusionGravitySignal : Signal {
    override val moduleId: Int get() = Module.SENSOR_FUSION.value
    override val registerId: Int get() = 0x09
    override val dataId: Int get() = 0xFF
    override val nChannels: Int get() = 3
    override val channelSize: Int get() = 4
    override val offset: Int get() = 0
    override val isSigned: Boolean get() = true
}

/** Linear-acceleration output of sensor fusion (3 × float32 = 12 bytes, signed). */
class SensorFusionLinearAccelerationSignal : Signal {
    override val moduleId: Int get() = Module.SENSOR_FUSION.value
    override val registerId: Int get() = 0x0A
    override val dataId: Int get() = 0xFF
    override val nChannels: Int get() = 3
    override val channelSize: Int get() = 4
    override val offset: Int get() = 0
    override val isSigned: Boolean get() = true
}

/**
 * Identifies a data processor created on the board.
 *
 * A handle conforms to [Signal] so it can be passed directly as the `source`
 * argument of `createProcessor(config, source)` to chain processors.
 */
data class ProcessorHandle(
    /** Board-assigned processor ID (0-based). */
    val id: Int,
    override val nChannels: Int,
    override val channelSize: Int,
    override val isSigned: Boolean,
) : Signal {
    // Signal routing to the processor NOTIFY register.
    override val moduleId: Int get() = Module.DATA_PROCESSOR.value
    override val registerId: Int get() = 0x03 // NOTIFY
    override val dataId: Int get() = id
    override val offset: Int get() = 0
}
