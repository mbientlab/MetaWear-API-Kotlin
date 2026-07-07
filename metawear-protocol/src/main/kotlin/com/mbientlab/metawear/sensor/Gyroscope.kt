package com.mbientlab.metawear.sensor

import com.mbientlab.metawear.model.CartesianFloat
import com.mbientlab.metawear.protocol.Command
import com.mbientlab.metawear.protocol.Loggable
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.protocol.Packet
import com.mbientlab.metawear.protocol.PacketParser
import kotlin.math.abs

// Gyroscope register opcodes (module 0x13) — names follow the C++ SDK headers
// (`GyroBosch.h`, `GyroBmi270.h`). The basic configure / enable / start / stop
// map is identical to the accelerometer module (registers 0x01–0x03); only the
// data and packed-data registers differ between chips, and the BMI270 adds a
// 0x06 offsets register:
//
//   0x01 POWER                  [mod, 0x01, 0x01] start; [mod, 0x01, 0x00] stop. Both chips.
//   0x02 DATA_INTERRUPT_ENABLE  [mod, 0x02, enable_mask, disable_mask]. Both chips.
//   0x03 DATA_INTERRUPT_CONFIG  [mod, 0x03, gyro_conf, range]. Both chips share encoding.
//   0x04 BMI270: DATA_INTERRUPT (raw subscribe / notify). BMI160 uses 0x05.
//   0x05 BMI160: DATA (raw subscribe / notify). BMI270: PACKED_GYRO_DATA.
//   0x06 BMI270 only: OFFSETS — writes signed (x, y, z) zero-offsets.
//   0x07 BMI160: PACKED_GYRO_DATA (3 samples per packet). BMI270 uses 0x05.

/**
 * Bosch BMI160 gyroscope (MetaMotion R / RL).
 * Streams angular velocity (degrees / second) from module 0x13.
 */
class GyroscopeBmi160(
    val odr: Odr = Odr.HZ100,
    val range: Range = Range.DPS2000,
) : BoschImuSensor {

    /** Output data rate. Raw values match C++ `MblMwGyroBoschOdr` (starts at 6). */
    enum class Odr(val raw: Int, val hz: Double) {
        HZ25(6, 25.0), HZ50(7, 50.0), HZ100(8, 100.0), HZ200(9, 200.0),
        HZ400(10, 400.0), HZ800(11, 800.0), HZ1600(12, 1600.0), HZ3200(13, 3200.0)
    }

    /** Full-scale range. Higher values trade resolution for headroom. */
    enum class Range(val configByte: Int, val scale: Float, val rangeDps: Float) {
        DPS2000(0, 16.4f, 2000f), DPS1000(1, 32.8f, 1000f), DPS500(2, 65.6f, 500f),
        DPS250(3, 131.2f, 250f), DPS125(4, 262.4f, 125f)
    }

    override val module: Module = Module.GYRO
    override val dataRegister: Int = 0x05          // BMI160 DATA
    override val packedDataRegister: Int? = 0x07   // BMI160 PACKED_GYRO_DATA
    override val loggerKey: String = "angular-velocity"

    /**
     * BMI160 + BMI270 use the identical `gyro_conf` encoding:
     *   bits [3:0] = ODR, bits [6:4] = BWP (always 2 = normal).
     */
    override val configPayload: ByteArray
        get() = byteArrayOf(((2 shl 4) or odr.raw).toByte(), range.configByte.toByte())

    override fun parseSample(packet: ByteArray): CartesianFloat =
        PacketParser.parseCartesianFloat(packet, range.scale)

    override fun parsePackedSamples(packet: ByteArray): List<CartesianFloat> =
        PacketParser.parsePackedCartesianFloat(packet, range.scale)
}

/**
 * Bosch BMI270 gyroscope (MetaMotion S).
 * Shares the `gyro_conf` encoding with the BMI160, but uses a different data
 * register and adds the BMI270-only [Offsets] calibration command.
 */
class GyroscopeBmi270(
    val odr: Odr = Odr.HZ100,
    val range: Range = Range.DPS2000,
) : BoschImuSensor {

    /** Output data rate. Raw values match C++ `MblMwGyroBoschOdr` (starts at 6). */
    enum class Odr(val raw: Int, val hz: Double) {
        HZ25(6, 25.0), HZ50(7, 50.0), HZ100(8, 100.0), HZ200(9, 200.0),
        HZ400(10, 400.0), HZ800(11, 800.0), HZ1600(12, 1600.0), HZ3200(13, 3200.0)
    }

    /** Full-scale range. Higher values trade resolution for headroom. */
    enum class Range(val configByte: Int, val scale: Float, val rangeDps: Float) {
        DPS2000(0, 16.4f, 2000f), DPS1000(1, 32.8f, 1000f), DPS500(2, 65.6f, 500f),
        DPS250(3, 131.2f, 250f), DPS125(4, 262.4f, 125f)
    }

    override val module: Module = Module.GYRO
    override val dataRegister: Int = 0x04          // BMI270 DATA_INTERRUPT
    override val packedDataRegister: Int? = 0x05   // BMI270 PACKED_GYRO_DATA
    override val loggerKey: String = "angular-velocity"

    override val configPayload: ByteArray
        get() = byteArrayOf(((2 shl 4) or odr.raw).toByte(), range.configByte.toByte())

    override fun parseSample(packet: ByteArray): CartesianFloat =
        PacketParser.parseCartesianFloat(packet, range.scale)

    override fun parsePackedSamples(packet: ByteArray): List<CartesianFloat> =
        PacketParser.parsePackedCartesianFloat(packet, range.scale)

    /**
     * BMI270-only zero-offset calibration. Mirrors C++
     * `mbl_mw_gyro_bmi270_offsets(board, x, y, z)`: writes signed per-axis
     * offsets to register 0x06; subsequent samples have the offsets applied in
     * hardware.
     */
    class Offsets(val x: Int, val y: Int, val z: Int) : Command {
        override val commandData: ByteArray
            get() = Packet.command(Module.GYRO, 0x06, x, y, z)
    }
}

/**
 * Type-erased gyroscope chosen at runtime from the board's reported chip ID.
 *
 * Use [make] to construct one from the implementation byte returned during
 * module discovery; the requested ODR / range are snapped to the nearest value
 * the underlying chip supports.
 */
sealed class Gyroscope : Loggable<CartesianFloat> {

    /** The board has a BMI160 gyroscope (impl id 0). */
    data class Bmi160(val sensor: GyroscopeBmi160) : Gyroscope()

    /** The board has a BMI270 gyroscope (impl id 1). */
    data class Bmi270(val sensor: GyroscopeBmi270) : Gyroscope()

    private val chip: Loggable<CartesianFloat>
        get() = when (this) {
            is Bmi160 -> sensor
            is Bmi270 -> sensor
        }

    // ---- Loggable forwarding ----

    override val module: Module get() = Module.GYRO
    override val dataRegister: Int get() = chip.dataRegister
    override val packedDataRegister: Int? get() = chip.packedDataRegister
    override val configureCommands: List<ByteArray> get() = chip.configureCommands
    override val enableCommand: ByteArray get() = chip.enableCommand
    override val startCommand: ByteArray get() = chip.startCommand
    override val stopCommand: ByteArray get() = chip.stopCommand
    override val disableCommand: ByteArray get() = chip.disableCommand
    override val loggerKey: String get() = "angular-velocity"

    override fun parseSample(packet: ByteArray): CartesianFloat = chip.parseSample(packet)

    override fun parsePackedSamples(packet: ByteArray): List<CartesianFloat> =
        chip.parsePackedSamples(packet)

    // ---- Snapped configuration ----

    /** Actual ODR after snapping to nearest supported value, in Hz. */
    val odrHz: Double
        get() = when (this) {
            is Bmi160 -> sensor.odr.hz
            is Bmi270 -> sensor.odr.hz
        }

    /** Actual range after snapping to nearest supported value, in dps. */
    val rangeDps: Float
        get() = when (this) {
            is Bmi160 -> sensor.range.rangeDps
            is Bmi270 -> sensor.range.rangeDps
        }

    /** Returns a new gyroscope with the ODR snapped to the nearest supported value. */
    fun withOdr(odrHz: Double): Gyroscope = when (this) {
        is Bmi160 -> Bmi160(GyroscopeBmi160(nearestOdrBmi160(odrHz), sensor.range))
        is Bmi270 -> Bmi270(GyroscopeBmi270(nearestOdrBmi270(odrHz), sensor.range))
    }

    /** Returns a new gyroscope with the range snapped to the nearest supported value. */
    fun withRange(rangeDps: Float): Gyroscope = when (this) {
        is Bmi160 -> Bmi160(GyroscopeBmi160(sensor.odr, nearestRangeBmi160(rangeDps)))
        is Bmi270 -> Bmi270(GyroscopeBmi270(sensor.odr, nearestRangeBmi270(rangeDps)))
    }

    companion object {
        /**
         * Build a type-erased gyroscope for the given chip impl id (0 = BMI160,
         * 1 = BMI270; other values return `null`), snapping ODR and range to the
         * nearest values supported by the chip.
         */
        fun make(impl: Int, odrHz: Double = 100.0, rangeDps: Float = 2000f): Gyroscope? =
            when (impl) {
                0 -> Bmi160(GyroscopeBmi160(nearestOdrBmi160(odrHz), nearestRangeBmi160(rangeDps)))
                1 -> Bmi270(GyroscopeBmi270(nearestOdrBmi270(odrHz), nearestRangeBmi270(rangeDps)))
                else -> null
            }

        private fun nearestOdrBmi160(odrHz: Double): GyroscopeBmi160.Odr =
            GyroscopeBmi160.Odr.entries.minByOrNull { abs(it.hz - odrHz) }!!

        private fun nearestRangeBmi160(rangeDps: Float): GyroscopeBmi160.Range =
            GyroscopeBmi160.Range.entries.minByOrNull { abs(it.rangeDps - rangeDps) }!!

        private fun nearestOdrBmi270(odrHz: Double): GyroscopeBmi270.Odr =
            GyroscopeBmi270.Odr.entries.minByOrNull { abs(it.hz - odrHz) }!!

        private fun nearestRangeBmi270(rangeDps: Float): GyroscopeBmi270.Range =
            GyroscopeBmi270.Range.entries.minByOrNull { abs(it.rangeDps - rangeDps) }!!
    }
}
