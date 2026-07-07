package com.mbientlab.metawear.sensor

import com.mbientlab.metawear.MetaWearDevice
import com.mbientlab.metawear.protocol.Command
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.protocol.Packet
import com.mbientlab.metawear.protocol.PacketParser
import com.mbientlab.metawear.protocol.Pollable

// Humidity (BME280). Port of MWHumidity.swift; mirrors C++
// `humidity_bme280.{h,cpp}`. The humidity module (0x16) is only present on
// MetaEnvironment boards, which carry a BME280 chip exposing relative humidity
// as a BME280_HUMIDITY fixed-point value (raw UInt32 / 1024).
//
// Registers:
//   HUMIDITY = 0x01   one-shot read (read bit → 0x81)
//   MODE     = 0x02   set oversampling mode
//
// Note: the Swift `MWHumidity: MWPolledLoggable` conformance (timer-driven
// on-board logging of reads) is part of the polled-logging surface and is not
// ported here.

/**
 * One-shot read of the BME280 relative-humidity signal (percent, 0-100).
 * Port of `MWHumidity` (Swift).
 *
 * Use [MetaWearDevice.readHumidity], or plug into the generic read pipeline as
 * any other [Pollable] sensor.
 */
class Humidity : Pollable<Float> {

    /**
     * Humidity oversampling mode. Raw values match the C++
     * `MBL_MW_HUMIDITY_BME280_OVERSAMPLING_*` constants (sequential 1-5).
     */
    enum class Oversampling(val raw: Int) {
        X1(1), X2(2), X4(3), X8(4), X16(5)
    }

    // ---- Sensor ----

    override val module: Module = Module.HUMIDITY
    override val dataRegister: Int = 0x01
    override val packedDataRegister: Int? = null

    // ---- Readable ----

    /** `[0x16, 0x81]` — register 0x01 with the read bit set. */
    override val readCommand: ByteArray get() = Packet.read(Module.HUMIDITY, 0x01)

    // Response: [module=0x16, register=0x81, b0, b1, b2, b3] — UInt32 LE raw / 1024.
    override fun parseSample(packet: ByteArray): Float = PacketParser.parseHumidity(packet)
}

/**
 * Configure the humidity sensor's oversampling mode. Port of
 * `MWHumiditySetOversampling` (Swift); mirrors C++
 * `mbl_mw_humidity_bme280_set_oversampling` — register 0x02, payload `[raw]`.
 *
 * Python reference vectors (from `test_humidity_bme280.py`):
 * ```
 * _1X  → [0x16, 0x02, 0x01]
 * _2X  → [0x16, 0x02, 0x02]
 * _4X  → [0x16, 0x02, 0x03]
 * _8X  → [0x16, 0x02, 0x04]
 * _16X → [0x16, 0x02, 0x05]
 * ```
 */
class HumiditySetOversampling(val oversampling: Humidity.Oversampling) : Command {
    override val commandData: ByteArray
        get() = Packet.command(Module.HUMIDITY, 0x02, oversampling.raw)
}

// ---- MetaWearDevice humidity convenience ----

/**
 * Read the current relative humidity from the BME280 sensor.
 *
 * @return Relative humidity as a percentage (0-100).
 * @throws com.mbientlab.metawear.model.MetaWearException.OperationFailed if the
 *   humidity module is not present or the response packet is malformed.
 */
suspend fun MetaWearDevice.readHumidity(): Float {
    val reader = Humidity()
    val packet = sendRead(
        command = reader.readCommand,
        awaitModule = Module.HUMIDITY,
        awaitRegister = 0x01,
    )
    return reader.parseSample(packet)
}

/**
 * Set the BME280 humidity oversampling mode. Higher oversampling reduces noise
 * at the cost of measurement latency.
 */
suspend fun MetaWearDevice.setHumidityOversampling(oversampling: Humidity.Oversampling) {
    send(HumiditySetOversampling(oversampling))
}
