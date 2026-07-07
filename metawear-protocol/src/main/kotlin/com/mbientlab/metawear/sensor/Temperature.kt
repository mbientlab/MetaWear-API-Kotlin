package com.mbientlab.metawear.sensor

import com.mbientlab.metawear.protocol.Command
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.protocol.Packet
import com.mbientlab.metawear.protocol.PacketParser
import com.mbientlab.metawear.protocol.Pollable

// Multi-channel temperature. Port of MWTemperature.swift; mirrors C++
// `multichanneltemperature.{h,cpp}`. The temperature module (0x04) exposes a
// variable number of channels, each backed by one of four source types. Board
// layouts vary:
//
//   MetaWear R    (2 channels): [NRF_DIE, EXT_THERM]
//   MetaWear RPro (4 channels): [NRF_DIE, PRESET_THERM, EXT_THERM, BMP280]
//
// Registers:
//   TEMPERATURE = 0x01   read one sample (read bit → 0x81; silent → 0xC1)
//   MODE        = 0x02   configure external thermistor pin mapping
//
// Note: the Swift `MWThermometer: MWPolledLoggable` conformance (timer-driven
// on-board logging of reads) is part of the polled-logging surface and is not
// ported here.

/**
 * Physical source backing one channel of the multi-channel temperature module.
 * Port of `MWThermometerSource` (Swift); raw values match C++
 * `MblMwTemperatureSource`.
 */
enum class ThermometerSource(val raw: Int) {
    /** Sentinel for unknown / out-of-range channel indices. */
    INVALID(-1),
    /** NRF SoC die temperature (always channel 0). */
    NRF_DIE(0),
    /** External thermistor wired to a GPIO pin. */
    EXT_THERMISTOR(1),
    /** Temperature sensor inside the BMP280 barometer. */
    BMP280(2),
    /** On-board preset thermistor (RPro only). */
    PRESET_THERMISTOR(3),
}

/**
 * One-shot temperature read from a specific channel on the temperature module.
 * Port of `MWTemperatureChannel` (Swift) — the backwards-compatible read-command
 * surface. The physical source at each channel depends on the board.
 */
class TemperatureChannel(
    /** Thermometer channel index (0-3 depending on board). */
    val channel: Int = 0,
) {

    /** Read command: `[0x04, 0x81, channel]` (register 0x01 | read bit 0x80). */
    val readCommand: ByteArray get() = Packet.read(Module.TEMPERATURE, 0x01, channel)

    /**
     * Silent read command: `[0x04, 0xC1, channel]` (adds the 0x40 silent bit).
     * Used when issuing a read without an active subscriber — the board returns
     * the sample exactly once and does not feed the notification dispatcher.
     */
    val silentReadCommand: ByteArray
        get() = Packet.command(Module.TEMPERATURE, 0x01 or 0x80 or 0x40, channel)

    // Convenience aliases for the RPro channel layout. If you are targeting a
    // MetaWear R board (2 channels), only NRF and the old EXTERNAL_THERMISTOR
    // alias (which is channel 1 on R) apply.
    companion object {
        /** NRF SoC die temperature. Channel 0 on all boards. */
        val NRF = TemperatureChannel(0)

        /**
         * External thermistor. Channel 1 on MetaWear R boards. On RPro boards
         * the external thermistor is at channel 2 — prefer
         * [EXTERNAL_THERMISTOR_RPRO] there.
         */
        val EXTERNAL_THERMISTOR = TemperatureChannel(1)

        /** Preset (on-board) thermistor on RPro. Channel 1 on RPro. */
        val PRESET_THERMISTOR = TemperatureChannel(1)

        /** External thermistor on RPro. Channel 2 on RPro. */
        val EXTERNAL_THERMISTOR_RPRO = TemperatureChannel(2)

        /** BMP280 barometer temperature on RPro. Channel 3. */
        val BMP280 = TemperatureChannel(3)

        /**
         * Legacy alias — prefer [EXTERNAL_THERMISTOR_RPRO] or [PRESET_THERMISTOR].
         * Kept for source compatibility. Resolves to channel 2 (RPro ext therm).
         */
        val BOSCH = TemperatureChannel(2)
    }
}

/**
 * One-shot temperature read (Celsius) from a single channel of the
 * multi-channel temperature module (0x04). Port of `MWThermometer` (Swift).
 * Drop-in [Pollable] wrapper around a channel index; use `silent = true` to
 * issue the read without firing the notification dispatcher.
 */
class Thermometer(
    val channel: Int,
    val silent: Boolean = false,
) : Pollable<Float> {

    override val module: Module = Module.TEMPERATURE
    override val dataRegister: Int = 0x01
    override val packedDataRegister: Int? = null

    override val readCommand: ByteArray
        get() {
            // 0x81 = read, 0xC1 = read | silent. Channel ID rides in the payload.
            val register = if (silent) 0x01 or 0x80 or 0x40 else 0x01 or 0x80
            return Packet.command(Module.TEMPERATURE, register, channel)
        }

    // Response: [module=0x04, register=0x81, channel, lo, hi] — signed Int16
    // (Celsius × 8) after the channel data-id byte.
    override fun parseSample(packet: ByteArray): Float = PacketParser.parseTemperature(packet)
}

/**
 * Command that configures an external thermistor's GPIO pin mapping for one
 * channel of the temperature module. Port of `MWThermometerConfigureExt`
 * (Swift); mirrors C++ `mbl_mw_multi_chnl_temp_configure_ext_thermistor`.
 *
 * Wire format: register 0x02, payload `[channel, dataPin, pulldownPin, activeHigh]`.
 */
class ThermometerConfigureExt(
    val channel: Int,
    val dataPin: Int,
    val pulldownPin: Int,
    val activeHigh: Boolean,
) : Command {
    override val commandData: ByteArray
        get() = Packet.command(
            Module.TEMPERATURE, 0x02,
            channel, dataPin, pulldownPin, if (activeHigh) 1 else 0,
        )
}
