package com.mbientlab.metawear.sensor

import com.mbientlab.metawear.model.CartesianFloat
import com.mbientlab.metawear.protocol.Loggable
import com.mbientlab.metawear.protocol.Packet

/**
 * Shared base for the four Bosch IMU sensors (BMI160 / BMI270 accelerometer and
 * gyroscope).
 *
 * They share an identical register pattern at the command level:
 *
 *   register 0x01  POWER                  →  [0x01] start, [0x00] stop
 *   register 0x02  DATA_INTERRUPT_ENABLE  →  [0x01, 0x00] enable, [0x00, 0x01] disable
 *   register 0x03  DATA_INTERRUPT_CONFIG  →  [conf_byte, range_byte]
 *
 * Only the per-chip [configPayload] encoding and the data-register opcodes
 * differ, so concrete sensors declare just those plus their scale factor.
 */
interface BoschImuSensor : Loggable<CartesianFloat> {

    /** The 2-byte chip-specific payload written to DATA_INTERRUPT_CONFIG (0x03). */
    val configPayload: ByteArray

    // The opcodes 0x01 / 0x02 / 0x03 are the standard sensor-module register
    // layout shared by every Bosch IMU; the wire bytes are identical across the
    // accelerometer (0x03) and gyroscope (0x13) modules.

    override val enableCommand: ByteArray get() = Packet.command(module, 0x02, 0x01, 0x00)
    override val startCommand: ByteArray get() = Packet.command(module, 0x01, 0x01)
    override val stopCommand: ByteArray get() = Packet.command(module, 0x01, 0x00)
    override val disableCommand: ByteArray get() = Packet.command(module, 0x02, 0x00, 0x01)
    override val configureCommands: List<ByteArray>
        get() = listOf(Packet.command(module, 0x03, configPayload))
}
