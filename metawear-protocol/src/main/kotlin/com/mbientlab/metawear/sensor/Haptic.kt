package com.mbientlab.metawear.sensor

import com.mbientlab.metawear.protocol.Command
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.protocol.Packet

// Port of MWHaptic.swift — haptic module (0x08) pulse commands.

/**
 * Triggers a single haptic (vibration motor) or buzzer pulse.
 * Port of `MWHaptic` (Swift).
 *
 * ```kotlin
 * device.send(Haptic.motor(dutyCycle = 80, pulseWidth = 500))
 * device.send(Haptic.buzzer(pulseWidth = 200))
 * ```
 */
object Haptic {

    /** Actuator type: ERM haptic motor or piezo buzzer. */
    enum class Mode(val raw: Int) {
        MOTOR(0x00),
        BUZZER(0x01),
    }

    /**
     * Command: fire one pulse on the haptic motor.
     *
     * @param dutyCycle Vibration strength, 0–100 (%).
     * @param pulseWidth Duration in milliseconds (max 65535).
     */
    fun motor(dutyCycle: Int = 100, pulseWidth: Int = 500): Pulse =
        Pulse(Mode.MOTOR, dutyCycle, pulseWidth)

    /**
     * Command: fire one pulse on the buzzer.
     *
     * @param pulseWidth Duration in milliseconds.
     */
    fun buzzer(pulseWidth: Int = 200): Pulse =
        Pulse(Mode.BUZZER, 100, pulseWidth)

    /**
     * One-shot pulse command for the haptic motor or buzzer. Prefer the
     * [Haptic.motor] / [Haptic.buzzer] factories over building this directly.
     */
    class Pulse(val mode: Mode, dutyCycle: Int, val pulseWidth: Int) : Command {

        /** Strength of the pulse (0–100 %), clamped at 100. */
        val dutyCycle: Int = minOf(dutyCycle, 100)

        /**
         * Command bytes: `[0x08, 0x01, dutyCycle_scaled, pulseWidth_lo, pulseWidth_hi, mode]`.
         * Motor duty cycle: 0–100 % → 0–248 (hardware range).
         * Buzzer always uses 0x7F regardless of dutyCycle.
         */
        override val commandData: ByteArray
            get() {
                val dcByte = if (mode == Mode.BUZZER) 0x7F else minOf(248, dutyCycle * 248 / 100)
                return Packet.command(
                    Module.HAPTIC, 0x01,
                    dcByte,
                    pulseWidth and 0xFF,
                    (pulseWidth shr 8) and 0xFF,
                    mode.raw,
                )
            }
    }
}
