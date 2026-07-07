package com.mbientlab.metawear.sensor

import com.mbientlab.metawear.protocol.LogChunk
import com.mbientlab.metawear.protocol.Loggable
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.protocol.Packet
import com.mbientlab.metawear.protocol.PacketParser
import com.mbientlab.metawear.protocol.Pollable
import com.mbientlab.metawear.protocol.Streamable

// Barometer (BMP280 / BME280). Mirrors C++
// `barometer_bosch.{h,cpp}`. Both chips share the same register map and
// configuration encoding — they differ only in the physical meaning of
// standby indices 6 and 7:
//   * BMP280 → 2000 ms / 4000 ms
//   * BME280 →   10 ms /   20 ms
//
// The `BoschBaroConfig` bitfield on the device (per `barometer_bosch.cpp`):
//
//   byte 0: [xx][p-os:3][t-os:3]
//   byte 1: [xx][iir:3][standby:3]
//
// where `t-os` (temperature oversampling) is kept at `ULTRA_LOW_POWER` (1)
// except when pressure oversampling is `ULTRA_HIGH`, in which case it is set
// to `LOW_POWER` (2).

/**
 * Streams atmospheric pressure (Pascals) from the BMP280 or BME280 barometer
 * (module 0x12).
 *
 * Use the BMP280 constructor (with [BmpStandbyTime]) for motion boards and the
 * BME280 constructor (with [BmeStandbyTime]) for environmental boards.
 */
class Barometer private constructor(
    val oversampling: Oversampling,
    val iirFilter: IirFilter,
    /** Raw standby-time index (0-7). Interpretation depends on chip variant. */
    val standbyRaw: Int,
    val variant: Variant?,
) : Loggable<Float> {

    /** Configure for a BMP280. */
    constructor(
        oversampling: Oversampling = Oversampling.STANDARD,
        iirFilter: IirFilter = IirFilter.OFF,
        standbyTime: BmpStandbyTime = BmpStandbyTime.MS0_5,
    ) : this(oversampling, iirFilter, standbyTime.raw, Variant.BMP280)

    /** Configure for a BME280. */
    constructor(
        oversampling: Oversampling,
        iirFilter: IirFilter,
        bmeStandbyTime: BmeStandbyTime,
    ) : this(oversampling, iirFilter, bmeStandbyTime.raw, Variant.BME280)

    /**
     * Chip variant. Matches C++ `MBL_MW_MODULE_BARO_TYPE_*` constants — these
     * show up in `moduleInfo(Module.BAROMETER)?.implementation`.
     */
    enum class Variant(val raw: Int) {
        BMP280(0),
        BME280(1),
    }

    /**
     * Pressure oversampling. Temperature oversampling is derived automatically
     * (ULTRA_LOW_POWER, or LOW_POWER when this is [ULTRA_HIGH]).
     */
    enum class Oversampling(val raw: Int) {
        SKIP(0), ULTRA_LOW_POWER(1), LOW_POWER(2), STANDARD(3), HIGH(4), ULTRA_HIGH(5)
    }

    /**
     * Built-in IIR low-pass filter coefficient. Higher averaging values reduce
     * noise at the cost of step response.
     */
    enum class IirFilter(val raw: Int) {
        OFF(0), AVG2(1), AVG4(2), AVG8(3), AVG16(4)
    }

    /** Standby time indices on the BMP280. Raw values 0-7. */
    enum class BmpStandbyTime(val raw: Int, val ms: Double) {
        MS0_5(0, 0.5), MS62_5(1, 62.5), MS125(2, 125.0), MS250(3, 250.0),
        MS500(4, 500.0), MS1000(5, 1000.0), MS2000(6, 2000.0), MS4000(7, 4000.0)
    }

    /** Standby time indices on the BME280. Indices 6/7 diverge from BMP280. */
    enum class BmeStandbyTime(val raw: Int, val ms: Double) {
        MS0_5(0, 0.5), MS62_5(1, 62.5), MS125(2, 125.0), MS250(3, 250.0),
        MS500(4, 500.0), MS1000(5, 1000.0), MS10(6, 10.0), MS20(7, 20.0)
    }

    // ---- Sensor ----

    override val module: Module = Module.BAROMETER
    override val dataRegister: Int = 0x01           // PRESSURE
    override val packedDataRegister: Int? = null

    // ---- Streamable ----

    override val configureCommands: List<ByteArray>
        get() {
            // Temperature oversampling mirrors C++ `mbl_mw_baro_bosch_set_oversampling`:
            // default ULTRA_LOW_POWER (1); LOW_POWER (2) when pressure == ULTRA_HIGH.
            val tempOs = if (oversampling == Oversampling.ULTRA_HIGH) 2 else 1
            val byte0 = (oversampling.raw shl 2) or (tempOs shl 5)
            val byte1 = (iirFilter.raw shl 2) or (standbyRaw shl 5)
            return listOf(Packet.command(Module.BAROMETER, 0x03, byte0, byte1))
        }

    override val enableCommand: ByteArray get() = Packet.command(Module.BAROMETER, 0x04, 0x01, 0x01)
    override val startCommand: ByteArray get() = Packet.command(Module.BAROMETER, 0x04, 0x01, 0x01)
    override val stopCommand: ByteArray get() = Packet.command(Module.BAROMETER, 0x04, 0x00, 0x00)
    override val disableCommand: ByteArray get() = Packet.command(Module.BAROMETER, 0x04, 0x00, 0x00)

    override fun parseSample(packet: ByteArray): Float = PacketParser.parsePressure(packet)

    // ---- Loggable ----
    // The barometer streams a single 4-byte UInt32 pressure sample. On-board
    // logging uses one 4-byte chunk rather than the IMU default of (4, 2).

    override val loggerKey: String = "pressure"
    override val logDataChunks: List<LogChunk> = listOf(LogChunk(0, 4))
}

/**
 * Streams altitude (meters) from the BMP280 / BME280 barometer.
 *
 * Backed by the same hardware as [Barometer] — the firmware computes altitude
 * from pressure and exposes it on register 0x02. Configuration is supplied via
 * a [Barometer] value.
 */
class Altimeter(
    val barometerConfig: Barometer = Barometer(),
) : Streamable<Float> {

    override val module: Module = Module.BAROMETER
    override val dataRegister: Int = 0x02           // ALTITUDE
    override val packedDataRegister: Int? = null

    override val configureCommands: List<ByteArray> get() = barometerConfig.configureCommands
    override val enableCommand: ByteArray get() = barometerConfig.enableCommand
    override val startCommand: ByteArray get() = barometerConfig.startCommand
    override val stopCommand: ByteArray get() = barometerConfig.stopCommand
    override val disableCommand: ByteArray get() = barometerConfig.disableCommand

    override fun parseSample(packet: ByteArray): Float = PacketParser.parseAltitude(packet)
}

/**
 * One-shot pressure read from the BMP280 / BME280 barometer. Mirrors C++
 * `mbl_mw_baro_bosch_get_pressure_read_data_signal`.
 *
 * Same register as the streaming pressure signal (0x01), but with the read bit
 * set — the firmware returns a single sample rather than enabling cyclic
 * notifications.
 */
class BarometerPressureRead : Pollable<Float> {

    override val module: Module = Module.BAROMETER
    override val dataRegister: Int = 0x01
    override val packedDataRegister: Int? = null

    /** `[0x12, 0x81]` — register 0x01 with the read bit set. */
    override val readCommand: ByteArray get() = Packet.read(Module.BAROMETER, 0x01)

    override fun parseSample(packet: ByteArray): Float = PacketParser.parsePressure(packet)
}
