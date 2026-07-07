package com.mbientlab.metawear.sensor

import com.mbientlab.metawear.protocol.Command
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.protocol.Packet
import java.util.UUID

// Port of MWiBeacon.swift — iBeacon module (0x07).

/**
 * Commands for configuring the MetaWear as a Bluetooth iBeacon advertiser.
 * Port of `MWiBeacon` (Swift).
 *
 * Typical usage:
 * ```kotlin
 * device.send(IBeacon.SetUuid(UUID.randomUUID()))
 * device.send(IBeacon.SetMajor(1))
 * device.send(IBeacon.SetMinor(2))
 * device.send(IBeacon.Enable())
 * // ...
 * device.send(IBeacon.Disable())
 * ```
 */
object IBeacon {

    // ---- Enable / Disable (register 0x01) ----

    /** Start iBeacon advertising. */
    class Enable : Command {
        override val commandData: ByteArray = Packet.command(Module.IBEACON, 0x01, 0x01)
    }

    /** Stop iBeacon advertising. */
    class Disable : Command {
        override val commandData: ByteArray = Packet.command(Module.IBEACON, 0x01, 0x00)
    }

    // ---- UUID (register 0x02) ----

    /**
     * Set the iBeacon proximity UUID.
     *
     * The MetaWear firmware expects the UUID payload in **little-endian** byte
     * order (per `ibeacon.h`: *"Byte representation of the UUID in little endian
     * ordering"*). The firmware reverses the bytes again when broadcasting so
     * they appear on-air in standard (big-endian) UUID form.
     *
     * Python reference vector (`test_ibeacon.py::test_set_uuid`):
     * ```
     * UUID(326A9006-85CB-9195-D9DD-464CFBBAE75A)
     *   → [0x07, 0x02,
     *      0x5A, 0xE7, 0xBA, 0xFB, 0x4C, 0x46, 0xDD, 0xD9,
     *      0x95, 0x91, 0xCB, 0x85, 0x06, 0x90, 0x6A, 0x32]
     * ```
     */
    class SetUuid(val uuid: UUID = UUID.randomUUID()) : Command {
        override val commandData: ByteArray
            get() {
                // `UUID`'s most/least significant bits are big-endian (standard)
                // order. Reverse to little-endian for the MetaWear wire format.
                val canonical = ByteArray(16)
                var msb = uuid.mostSignificantBits
                var lsb = uuid.leastSignificantBits
                for (i in 7 downTo 0) {
                    canonical[i] = (msb and 0xFF).toByte()
                    msb = msb ushr 8
                }
                for (i in 15 downTo 8) {
                    canonical[i] = (lsb and 0xFF).toByte()
                    lsb = lsb ushr 8
                }
                return Packet.command(Module.IBEACON, 0x02, canonical.reversedArray())
            }
    }

    // ---- Major / Minor (registers 0x03, 0x04) ----

    /** Set the iBeacon major value (0–65535). */
    class SetMajor(val major: Int) : Command {
        override val commandData: ByteArray
            get() = Packet.command(Module.IBEACON, 0x03, major and 0xFF, (major shr 8) and 0xFF)
    }

    /** Set the iBeacon minor value (0–65535). */
    class SetMinor(val minor: Int) : Command {
        override val commandData: ByteArray
            get() = Packet.command(Module.IBEACON, 0x04, minor and 0xFF, (minor shr 8) and 0xFF)
    }

    // ---- RX / TX power (registers 0x05, 0x06) ----

    /**
     * Set the received signal power at 1 metre, broadcast in the advertisement
     * payload. Used by receivers for ranging. Typical value: –55 dBm (0xC9 on
     * the wire as an unsigned byte).
     */
    class SetRxPower(val power: Int = -55) : Command {
        override val commandData: ByteArray
            get() = Packet.command(Module.IBEACON, 0x05, power and 0xFF)
    }

    /**
     * Set the actual BLE TX power used during iBeacon advertisements.
     * Typical values: 0, –4, –8, –12, –16, –20 dBm.
     */
    class SetTxPower(val power: Int = 0) : Command {
        override val commandData: ByteArray
            get() = Packet.command(Module.IBEACON, 0x06, power and 0xFF)
    }

    // ---- Advertisement period (register 0x07) ----

    /** Set the iBeacon advertisement period in milliseconds (default 700 ms). */
    class SetPeriod(val periodMs: Int = 700) : Command {
        override val commandData: ByteArray
            get() = Packet.command(Module.IBEACON, 0x07, periodMs and 0xFF, (periodMs shr 8) and 0xFF)
    }
}
