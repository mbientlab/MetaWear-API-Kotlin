package com.mbientlab.metawear.sensor

import com.mbientlab.metawear.MetaWearDevice
import com.mbientlab.metawear.model.MetaWearException
import com.mbientlab.metawear.protocol.Command
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.protocol.Packet
import com.mbientlab.metawear.protocol.PacketParser
import com.mbientlab.metawear.protocol.Streamable

// GPIO module (0x05).

/**
 * Commands and streaming types for the MetaWear GPIO module.
 *
 * Pins are referenced by their 0-based index on the board connector. Typical
 * MetaWear boards expose pins 0–3; check the board's hardware guide for the
 * exact count and capabilities.
 *
 * Usage — read a digital input:
 * ```kotlin
 * val high = device.readDigital(pin = 0)
 * ```
 *
 * Usage — stream pin-change notifications:
 * ```kotlin
 * val changes = device.startStream(GpioPinChange(pin = 0, changeType = Gpio.ChangeType.ANY))
 * changes.collect { println("Pin 0 is now " + if (it.value.isHigh) "HIGH" else "LOW") }
 * ```
 */
object Gpio {

    // ---- Pull configuration ----

    /** Configures the internal pull resistor on a digital input pin. */
    enum class Pull(
        /** Command register that applies this pull mode. */
        val register: Int,
    ) {
        /** Internal pull-up resistor active (~50 kΩ to VDD). */
        UP(0x03),

        /** Internal pull-down resistor active (~50 kΩ to GND). */
        DOWN(0x04),

        /** No pull — the pin floats if undriven. */
        NONE(0x05),
    }

    // ---- Pin-change type ----

    /** Which edge(s) on the pin trigger a notification. */
    enum class ChangeType(val raw: Int) {
        /** Notify on rising edge (LOW → HIGH). */
        RISING(1),

        /** Notify on falling edge (HIGH → LOW). */
        FALLING(2),

        /** Notify on any edge. */
        ANY(3),
    }

    // ---- Analog read mode ----

    /** Selects how an analog input pin is sampled. */
    enum class AnalogReadMode(
        /** Raw register byte (no read bit). */
        val register: Int,
    ) {
        /**
         * Read the pin's voltage against the absolute reference (register 0x06).
         * Useful for an absolute millivolt reading.
         */
        ABSOLUTE_REFERENCE(0x06),

        /**
         * Read the pin's voltage as a ratio of the supply (register 0x07).
         * Raw ADC count, 0–1023.
         */
        ADC(0x07);

        /** Register byte with the read bit set (0x80). */
        val readRegister: Int get() = register or 0x80

        /**
         * Register byte with read (0x80) and silent (0x40) bits set. Silent
         * reads emit a response once and do not feed the notification dispatcher.
         */
        val silentReadRegister: Int get() = register or 0xC0
    }

    // ---- Enhanced analog read parameters ----

    /**
     * Optional pullup/pulldown/virtual-pin/delay parameters for an enhanced
     * analog read. Only honoured by GPIO module revision ≥ 2.
     *
     * Mirrors C++ `MblMwGpioAnalogReadParameters`. `delay_us` is packed into
     * one byte by shifting right by 2 (the firmware divides by 4 internally),
     * so the payload accepts values in the range [0, 1020] µs.
     */
    class AnalogReadParameters(
        /** Pin to drive high before sampling, or [UNUSED_PIN]. */
        val pullupPin: Int = UNUSED_PIN,
        /** Pin to drive low before sampling, or [UNUSED_PIN]. */
        val pulldownPin: Int = UNUSED_PIN,
        /** Pin ID the response is tagged with, or [UNUSED_PIN] to use the physical pin. */
        val virtualPin: Int = UNUSED_PIN,
        delayMicroseconds: Int = 0,
    ) {
        /** How long to wait between pull-setup and sampling, in microseconds (0–1020). */
        val delayMicroseconds: Int = minOf(delayMicroseconds, 1020)

        /** Delay byte — firmware divides by 4, so we pre-shift. */
        val encodedDelay: Int get() = delayMicroseconds shr 2

        companion object {
            /** Sentinel matching C++ `MBL_MW_GPIO_UNUSED_PIN`. */
            const val UNUSED_PIN: Int = 0xFF

            /**
             * Default parameter set: all pins unused, zero delay.
             * Byte layout emitted: `[0xFF, 0xFF, 0x00, 0xFF]`.
             */
            val DEFAULTS = AnalogReadParameters()
        }
    }

    // ---- Commands ----

    /** Drive a digital output pin HIGH. */
    data class SetHigh(val pin: Int) : Command {
        override val commandData: ByteArray get() = Packet.command(Module.GPIO, 0x01, pin)
    }

    /** Drive a digital output pin LOW. */
    data class SetLow(val pin: Int) : Command {
        override val commandData: ByteArray get() = Packet.command(Module.GPIO, 0x02, pin)
    }

    /** Set the pull configuration on a digital input pin. */
    data class SetPull(val pin: Int, val pull: Pull) : Command {
        override val commandData: ByteArray get() = Packet.command(Module.GPIO, pull.register, pin)
    }

    /** Configure which edge(s) on a pin trigger a change notification. */
    data class ConfigurePinChange(val pin: Int, val type: ChangeType) : Command {
        override val commandData: ByteArray get() = Packet.command(Module.GPIO, 0x09, pin, type.raw)
    }

    /** Begin pin-change monitoring on a pin. Mirrors `mbl_mw_gpio_start_pin_monitoring`. */
    data class StartPinMonitor(val pin: Int) : Command {
        override val commandData: ByteArray get() = Packet.command(Module.GPIO, 0x0B, pin, 0x01)
    }

    /** Stop pin-change monitoring on a pin. Mirrors `mbl_mw_gpio_stop_pin_monitoring`. */
    data class StopPinMonitor(val pin: Int) : Command {
        override val commandData: ByteArray get() = Packet.command(Module.GPIO, 0x0B, pin, 0x00)
    }

    // ---- Read command builders ----

    /**
     * Build a digital-input one-shot read command.
     *
     * @param pin GPIO pin index.
     * @param silent When `true`, emits `0xC8` (read + silent bit). The firmware
     *   responds once but does not push the sample through the notification
     *   dispatcher.
     */
    data class DigitalRead(val pin: Int, val silent: Boolean = false) : Command {
        override val commandData: ByteArray
            get() = Packet.command(Module.GPIO, if (silent) 0xC8 else 0x88, pin)
    }

    /**
     * Build an analog-input one-shot read command — basic or enhanced form.
     *
     * - When [parameters] is `null`, a 3-byte command is produced (pre-revision-2 boards).
     * - When [parameters] is non-`null`, a 7-byte command is produced with the
     *   pullup/pulldown/delay/virtual-pin fields (revision ≥ 2 boards).
     */
    class AnalogRead(
        val mode: AnalogReadMode,
        val pin: Int,
        val silent: Boolean = false,
        val parameters: AnalogReadParameters? = null,
    ) : Command {
        override val commandData: ByteArray
            get() {
                val register = if (silent) mode.silentReadRegister else mode.readRegister
                return if (parameters != null) {
                    Packet.command(
                        Module.GPIO, register, pin,
                        parameters.pullupPin, parameters.pulldownPin,
                        parameters.encodedDelay, parameters.virtualPin,
                    )
                } else {
                    Packet.command(Module.GPIO, register, pin)
                }
            }
    }
}

// ---- GPIO pin-change stream ----

/** The state of a GPIO pin at the moment of a change notification. */
data class GpioSample(
    /** 0-based pin index that triggered the notification. */
    val pin: Int,
    /** `true` when the pin is HIGH, `false` when LOW. */
    val isHigh: Boolean,
)

/** A streamable signal that fires on every configured edge of a GPIO pin. */
class GpioPinChange(
    val pin: Int,
    val changeType: Gpio.ChangeType = Gpio.ChangeType.ANY,
) : Streamable<GpioSample> {

    override val module: Module = Module.GPIO
    override val dataRegister: Int = 0x0A          // PIN_CHANGE_NOTIFY

    override val configureCommands: List<ByteArray>
        get() = listOf(Packet.command(Module.GPIO, 0x09, pin, changeType.raw)) // ConfigurePinChange

    override val enableCommand: ByteArray get() = Packet.command(Module.GPIO, 0x0B, pin, 0x01)
    override val startCommand: ByteArray = ByteArray(0)   // no global start needed
    override val stopCommand: ByteArray = ByteArray(0)
    override val disableCommand: ByteArray get() = Packet.command(Module.GPIO, 0x0B, pin, 0x00)

    override fun parseSample(packet: ByteArray): GpioSample {
        if (packet.size < 4) {
            throw MetaWearException.OperationFailed("GPIO pin-change packet too short: ${packet.size}")
        }
        // Packet: [0x05, 0x0A, pin, state]
        return GpioSample(pin = packet[2].toInt() and 0xFF, isHigh = packet[3].toInt() != 0)
    }
}

// ---- MetaWearDevice GPIO convenience ----

/**
 * Read the current state of a digital input pin.
 * @return `true` if the pin is HIGH.
 */
suspend fun MetaWearDevice.readDigital(pin: Int): Boolean {
    val packet = sendRead(
        command = Packet.command(Module.GPIO, 0x88, pin),
        awaitModule = Module.GPIO,
        awaitRegister = 0x08,
    )
    if (packet.size < 4) {
        throw MetaWearException.OperationFailed("GPIO digital read response too short")
    }
    return packet[3].toInt() != 0
}

/**
 * Read the analog value from a pin using the absolute voltage reference.
 *
 * Pass [parameters] for the enhanced read form with optional
 * pullup/pulldown/delay/virtual pin (board GPIO module revision must be ≥ 2
 * for the parameters to be honoured).
 *
 * @return Voltage in millivolts (raw ADC value, 0–65535).
 */
suspend fun MetaWearDevice.readAnalogAbsolute(
    pin: Int,
    parameters: Gpio.AnalogReadParameters? = null,
): Int {
    val cmd = Gpio.AnalogRead(mode = Gpio.AnalogReadMode.ABSOLUTE_REFERENCE, pin = pin, parameters = parameters)
    val packet = sendRead(cmd.commandData, awaitModule = Module.GPIO, awaitRegister = 0x06)
    if (packet.size < 5) {
        throw MetaWearException.OperationFailed("GPIO analog (abs) read response too short")
    }
    return PacketParser.parseUInt16LE(packet, 3)
}

/**
 * Read the raw ADC value from an analog input pin.
 *
 * Pass [parameters] for the enhanced read form with optional
 * pullup/pulldown/delay/virtual pin (board GPIO module revision must be ≥ 2
 * for the parameters to be honoured).
 *
 * @return Raw 10-bit ADC count (0–1023).
 */
suspend fun MetaWearDevice.readAnalogADC(
    pin: Int,
    parameters: Gpio.AnalogReadParameters? = null,
): Int {
    val cmd = Gpio.AnalogRead(mode = Gpio.AnalogReadMode.ADC, pin = pin, parameters = parameters)
    val packet = sendRead(cmd.commandData, awaitModule = Module.GPIO, awaitRegister = 0x07)
    if (packet.size < 5) {
        throw MetaWearException.OperationFailed("GPIO analog (ADC) read response too short")
    }
    return PacketParser.parseUInt16LE(packet, 3)
}
