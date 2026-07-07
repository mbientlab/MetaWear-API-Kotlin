package com.mbientlab.metawear.sensor

import com.mbientlab.metawear.model.CartesianFloat
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.protocol.PacketParser

/**
 * Bosch BMI160 accelerometer (MetaMotion R / RL — model 5). Samples are
 * `CartesianFloat` in units of g.
 *
 * Scale factors (LSB/g): ±2g = 16384, ±4g = 8192, ±8g = 4096, ±16g = 2048.
 */
class AccelerometerBmi160(
    val odr: Odr = Odr.HZ100,
    val range: Range = Range.G2,
) : BoschImuSensor {

    /** BMI160 output data rates. Rates below 12.5 Hz use the chip's under-sampling mode. */
    enum class Odr(val raw: Int, val hz: Double) {
        HZ0_78(0, 0.78125), HZ1_56(1, 1.5625), HZ3_12(2, 3.125), HZ6_25(3, 6.25),
        HZ12_5(4, 12.5), HZ25(5, 25.0), HZ50(6, 50.0), HZ100(7, 100.0),
        HZ200(8, 200.0), HZ400(9, 400.0), HZ800(10, 800.0), HZ1600(11, 1600.0);

        /** Byte written to the config register (enum value + 1). */
        val configByte: Int get() = raw + 1

        /** Under-sampling flag: required for ODR < 12.5 Hz. */
        val underSampling: Boolean get() = raw < 4
    }

    /** BMI160 accelerometer full-scale range. */
    enum class Range(val configByte: Int, val scale: Float, val rangeG: Float) {
        G2(0x03, 16384f, 2f), G4(0x05, 8192f, 4f), G8(0x08, 4096f, 8f), G16(0x0C, 2048f, 16f)
    }

    override val module: Module = Module.ACCELEROMETER
    override val dataRegister: Int = 0x04
    override val packedDataRegister: Int? = 0x1C   // BMI160 packed register
    override val loggerKey: String = "acceleration"

    /**
     * BMI160 `acc_conf` encoding:
     *   bits [3:0] = acc_odr (1-indexed), bits [6:4] = acc_bwp (2 normal / 0 when
     *   under-sampling), bit 7 = acc_us (under-sampling).
     */
    override val configPayload: ByteArray
        get() {
            val bwp = if (odr.underSampling) 0 else 2
            val us = if (odr.underSampling) 0x80 else 0x00
            val confByte = us or (bwp shl 4) or odr.configByte
            return byteArrayOf(confByte.toByte(), range.configByte.toByte())
        }

    override fun parseSample(packet: ByteArray): CartesianFloat =
        PacketParser.parseCartesianFloat(packet, range.scale)

    override fun parsePackedSamples(packet: ByteArray): List<CartesianFloat> =
        PacketParser.parsePackedCartesianFloat(packet, range.scale)
}

/**
 * Bosch BMI270 accelerometer (MetaMotion S — model 8). Same ODR / range
 * options as the BMI160; the config-byte encoding and packed-data register
 * differ.
 */
class AccelerometerBmi270(
    val odr: Odr = Odr.HZ100,
    val range: Range = Range.G2,
) : BoschImuSensor {

    enum class Odr(val raw: Int, val hz: Double) {
        HZ0_78(0, 0.78125), HZ1_56(1, 1.5625), HZ3_12(2, 3.125), HZ6_25(3, 6.25),
        HZ12_5(4, 12.5), HZ25(5, 25.0), HZ50(6, 50.0), HZ100(7, 100.0),
        HZ200(8, 200.0), HZ400(9, 400.0), HZ800(10, 800.0), HZ1600(11, 1600.0);

        val configByte: Int get() = raw + 1
        val underSampling: Boolean get() = raw < 4
    }

    /** BMI270 full-scale range. Range byte is 0-based (vs the BMI160's table). */
    enum class Range(val configByte: Int, val scale: Float, val rangeG: Float) {
        G2(0x00, 16384f, 2f), G4(0x01, 8192f, 4f), G8(0x02, 4096f, 8f), G16(0x03, 2048f, 16f)
    }

    override val module: Module = Module.ACCELEROMETER
    override val dataRegister: Int = 0x04
    override val packedDataRegister: Int? = 0x05   // BMI270 packed register
    override val loggerKey: String = "acceleration"

    /**
     * BMI270 `acc_conf` encoding:
     *   bits [3:0] = acc_odr (1-indexed), bits [6:4] = acc_bwp (always 2),
     *   bit 7 = acc_filter_perf (1 for ODR ≥ 12.5 Hz).
     */
    override val configPayload: ByteArray
        get() {
            val perf = if (odr.underSampling) 0x00 else 0x80
            val confByte = perf or (2 shl 4) or odr.configByte
            return byteArrayOf(confByte.toByte(), range.configByte.toByte())
        }

    override fun parseSample(packet: ByteArray): CartesianFloat =
        PacketParser.parseCartesianFloat(packet, range.scale)

    override fun parsePackedSamples(packet: ByteArray): List<CartesianFloat> =
        PacketParser.parsePackedCartesianFloat(packet, range.scale)
}
