package com.mbientlab.metawear.sensor

import com.mbientlab.metawear.protocol.Command
import com.mbientlab.metawear.protocol.LogChunk
import com.mbientlab.metawear.protocol.Loggable
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.protocol.Packet
import com.mbientlab.metawear.protocol.PacketParser

// Ambient Light (LTR329). Mirrors C++
// `ambientlight_ltr329.{h,cpp}`. The ambient-light module (0x14) is present on
// MetaWear RPro / MotionR boards, wrapping a Lite-On LTR329ALS sensor.
//
// Registers:
//   ENABLE = 0x01    start/stop sampling
//   CONFIG = 0x02    gain / integration-time / measurement-rate bitfield
//   OUTPUT = 0x03    UINT32 illuminance stream
//
// C++ config bitfield (`Ltr329Config`) packs into two bytes:
//   byte 0: bits 2-4 = als_gain     (bits 0-1, 5-7 reserved)
//   byte 1: bits 0-2 = als_measurement_rate
//           bits 3-5 = als_integration_time (bits 6-7 reserved)
//
// Gain encoding has a two-slot gap per the LTR329 datasheet: enum values 0-3
// map directly, but 48X / 96X map to 6 / 7 respectively (C++ adds +2).

/**
 * Streams illuminance from the LTR329 ambient-light sensor (module 0x14).
 *
 * Samples are raw illuminance values (milli-lux, unsigned 32-bit as `Long`);
 * use [AmbientLight.lux] to convert to lux.
 */
class AmbientLight(
    val gain: Gain = Gain.X1,
    val integrationTime: IntegrationTime = IntegrationTime.MS100,
    val measurementRate: MeasurementRate = MeasurementRate.MS500,
) : Loggable<Long> {

    /**
     * LTR329 illuminance gain. Raw values match C++ `MblMwAlsLtr329Gain`
     * (dense 0-5); the hardware register skips 4-5 and encodes 48X / 96X as
     * 6 / 7, which [registerValue] handles.
     */
    enum class Gain(val raw: Int) {
        /** `[1, 64 k]` lux (default). */
        X1(0),
        /** `[0.5, 32 k]` lux. */
        X2(1),
        /** `[0.25, 16 k]` lux. */
        X4(2),
        /** `[0.125, 8 k]` lux. */
        X8(3),
        /** `[0.02, 1.3 k]` lux. */
        X48(4),
        /** `[0.01, 600]` lux. */
        X96(5);

        /** Encoded value for the LTR329 `als_gain` bitfield (3 bits, bits 2-4 of byte 0). */
        val registerValue: Int
            get() = when (this) {
                X48 -> 6
                X96 -> 7
                else -> raw
            }
    }

    /**
     * Measurement time for each full ALS cycle. Raw values match C++
     * `MblMwAlsLtr329IntegrationTime` (enum order is not sorted by milliseconds
     * on purpose — 100 ms is the default at index 0).
     */
    enum class IntegrationTime(val raw: Int, val milliseconds: Int) {
        /** Default 100 ms integration time. */
        MS100(0, 100),
        MS50(1, 50),
        MS200(2, 200),
        MS400(3, 400),
        MS150(4, 150),
        MS250(5, 250),
        MS300(6, 300),
        MS350(7, 350),
    }

    /**
     * How frequently the illuminance register is updated. Raw values match C++
     * `MblMwAlsLtr329MeasurementRate`.
     */
    enum class MeasurementRate(val raw: Int, val milliseconds: Int) {
        MS50(0, 50),
        MS100(1, 100),
        MS200(2, 200),
        /** Default 500 ms measurement rate. */
        MS500(3, 500),
        MS1000(4, 1000),
        MS2000(5, 2000),
    }

    // ---- Sensor ----

    override val module: Module = Module.AMBIENT_LIGHT
    override val dataRegister: Int = 0x03       // OUTPUT
    override val packedDataRegister: Int? = null

    // ---- Streamable ----

    /** Config byte 0: gain in bits 2-4 (`gain.registerValue << 2`). */
    internal val configByte0: Int get() = gain.registerValue shl 2

    /** Config byte 1: measurement rate in bits 0-2, integration time in bits 3-5. */
    internal val configByte1: Int get() = measurementRate.raw or (integrationTime.raw shl 3)

    override val configureCommands: List<ByteArray>
        get() = listOf(Packet.command(Module.AMBIENT_LIGHT, 0x02, configByte0, configByte1))

    // The LTR329 module has no separate enable/disable interrupt — the start
    // command doubles as enable. Keep `enableCommand` as a no-op so the
    // streaming pipeline can emit it harmlessly (empty commands are dropped).
    override val enableCommand: ByteArray get() = ByteArray(0)
    override val disableCommand: ByteArray get() = ByteArray(0)

    override val startCommand: ByteArray get() = Packet.command(Module.AMBIENT_LIGHT, 0x01, 0x01)
    override val stopCommand: ByteArray get() = Packet.command(Module.AMBIENT_LIGHT, 0x01, 0x00)

    override fun parseSample(packet: ByteArray): Long = PacketParser.parseIlluminanceRaw(packet)

    // ---- Loggable ----
    // LTR329 illuminance streams a single 4-byte UInt32 sample (raw milli-lux).
    // One 4-byte log chunk instead of the IMU default of (4, 2).

    override val loggerKey: String = "illuminance"
    override val logDataChunks: List<LogChunk> = listOf(LogChunk(0, 4))

    companion object {
        /** Convert a raw illuminance sample (milli-lux) to lux. */
        fun lux(raw: Long): Float = raw.toFloat() / 1000.0f
    }
}

/**
 * One-shot command that writes an [AmbientLight] configuration (gain,
 * integration time, measurement rate) without starting / stopping the sensor.
 * Mirrors C++ `mbl_mw_als_ltr329_write_config`.
 */
class AmbientLightWriteConfig(val config: AmbientLight) : Command {
    override val commandData: ByteArray
        get() = Packet.command(Module.AMBIENT_LIGHT, 0x02, config.configByte0, config.configByte1)
}
