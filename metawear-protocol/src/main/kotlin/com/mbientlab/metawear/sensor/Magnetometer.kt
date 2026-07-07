package com.mbientlab.metawear.sensor

import com.mbientlab.metawear.model.CartesianFloat
import com.mbientlab.metawear.protocol.Command
import com.mbientlab.metawear.protocol.Loggable
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.protocol.Packet
import com.mbientlab.metawear.protocol.PacketParser

/**
 * Bosch BMM150 magnetometer (module 0x15). Port of `MWMagnetometer` (Swift).
 * Streams magnetic field in µT (16 LSB/µT).
 *
 * Construct from a Bosch-recommended [Preset] for typical use, or supply manual
 * `xyReps` / `zReps` / `odr` values for fine control.
 *
 * Register map:
 *   0x01 POWER_MODE   [0x01] start, [0x00] stop, [0x02] suspend (rev >= 2)
 *   0x02 DATA_INTERRUPT_ENABLE  [enable_mask, disable_mask]
 *   0x03 DATA_RATE    [odr]
 *   0x04 REPETITIONS  [xy_byte, z_byte]
 *   0x05 MAG_DATA
 *   0x09 PACKED_MAG_DATA (revision >= 1)
 */
class Magnetometer private constructor(
    /** Repetitions on the x/y axis (encoded on the wire as `(xyReps - 1) / 2`). */
    val xyReps: Int,
    /** Repetitions on the z axis (encoded on the wire as `zReps - 1`). */
    val zReps: Int,
    /** Output data rate. */
    val odr: Odr,
    /** The preset used to construct this configuration, or `null` for manual. */
    val preset: Preset?,
) : Loggable<CartesianFloat> {

    /** Configure from a recommended preset. */
    constructor(preset: Preset = Preset.LOW_POWER) :
        this(preset.xyReps, preset.zReps, preset.odr, preset)

    /** Manual configuration; mirrors C++ `mbl_mw_mag_bmm150_configure`. */
    constructor(xyReps: Int, zReps: Int, odr: Odr) : this(xyReps, zReps, odr, null)

    /**
     * Preset power modes recommended by Bosch. Each preset balances current
     * draw and noise floor at a fixed ODR / repetition count. Mirrors C++
     * `MblMwMagBmm150Preset`.
     */
    enum class Preset(val xyReps: Int, val zReps: Int, val odr: Odr) {
        /** 10 Hz, 170 µA, ~1.0 µT noise — recommended for most use cases. */
        LOW_POWER(3, 3, Odr.HZ10),
        /** 10 Hz, 0.5 mA, 0.6 µT noise. */
        REGULAR(9, 15, Odr.HZ10),
        /** 10 Hz, 0.8 mA, 0.5 µT noise. */
        ENHANCED_REGULAR(15, 27, Odr.HZ10),
        /** 20 Hz, 4.9 mA, 0.3 µT noise. */
        HIGH_ACCURACY(47, 83, Odr.HZ20),
    }

    /** Output data rate. Raw values match C++ `MblMwMagBmm150Odr`. */
    enum class Odr(val raw: Int, val hz: Double) {
        HZ10(0, 10.0), HZ2(1, 2.0), HZ6(2, 6.0), HZ8(3, 8.0),
        HZ15(4, 15.0), HZ20(5, 20.0), HZ25(6, 25.0), HZ30(7, 30.0)
    }

    override val module: Module = Module.MAGNETOMETER
    override val dataRegister: Int = 0x05          // MAG_DATA
    override val packedDataRegister: Int? = 0x09   // PACKED_MAG_DATA (revision >= 1)
    override val loggerKey: String = "magnetic-field"

    // BMM150 cold-boot workaround — matches MetaWear-Swift-Combine-SDK's
    // `streamSignal`: drop POWER_MODE to SLEEP and let the chip settle before
    // writing the REPETITIONS / DATA_RATE / DATA_INTERRUPT / POWER_MODE bytes.
    // Without this, a freshly-powered MetaMotion silently produces zero samples.
    override val warmupCommands: List<ByteArray>
        get() = listOf(Packet.command(Module.MAGNETOMETER, 0x01, 0x00))  // POWER_MODE = SLEEP
    override val warmupDelayNanos: Long get() = 200_000_000L             // 200 ms

    override val configureCommands: List<ByteArray>
        get() = listOf(
            // XY reps byte = (xy_reps - 1) / 2, Z reps byte = z_reps - 1
            Packet.command(Module.MAGNETOMETER, 0x04, (xyReps - 1) / 2, zReps - 1),
            Packet.command(Module.MAGNETOMETER, 0x03, odr.raw),
        )

    override val enableCommand: ByteArray get() = Packet.command(Module.MAGNETOMETER, 0x02, 0x01, 0x00)
    override val startCommand: ByteArray get() = Packet.command(Module.MAGNETOMETER, 0x01, 0x01)
    override val stopCommand: ByteArray get() = Packet.command(Module.MAGNETOMETER, 0x01, 0x00)
    override val disableCommand: ByteArray get() = Packet.command(Module.MAGNETOMETER, 0x02, 0x00, 0x01)

    override fun parseSample(packet: ByteArray): CartesianFloat =
        PacketParser.parseCartesianFloat(packet, SCALE)

    override fun parsePackedSamples(packet: ByteArray): List<CartesianFloat> =
        PacketParser.parsePackedCartesianFloat(packet, SCALE)

    /**
     * Suspend the magnetometer (POWER_MODE = 2). Mirrors C++
     * `mbl_mw_mag_bmm150_suspend`.
     *
     * The C++ implementation gates this on module revision >= 2
     * (SUSPEND_REVISION); on older firmware the command is silently dropped.
     * Callers are expected to check the magnetometer module revision before
     * sending this command.
     */
    class Suspend : Command {
        override val commandData: ByteArray
            get() = Packet.command(Module.MAGNETOMETER, 0x01, 0x02)
    }

    /**
     * One-shot configure command for the BMM150. Mirrors C++
     * `mbl_mw_mag_bmm150_configure`: writes both REPETITIONS (register 0x04)
     * and DATA_RATE (register 0x03) without starting / stopping the sensor,
     * bypassing the [Preset] helper. Both register writes are concatenated in
     * one byte blob, matching the Swift command's single `Data`.
     */
    class Configure(val xyReps: Int, val zReps: Int, val odr: Odr) : Command {
        override val commandData: ByteArray
            get() = Packet.command(Module.MAGNETOMETER, 0x04, (xyReps - 1) / 2, zReps - 1) +
                Packet.command(Module.MAGNETOMETER, 0x03, odr.raw)
    }

    companion object {
        /** 16 LSB/µT. */
        private const val SCALE: Float = 16.0f
    }
}
