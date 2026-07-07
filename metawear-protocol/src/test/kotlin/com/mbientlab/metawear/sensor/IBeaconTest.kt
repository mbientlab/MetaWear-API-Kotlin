package com.mbientlab.metawear.sensor

import com.mbientlab.metawear.bytes
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Ported from MWiBeaconTests.swift — iBeacon command byte-layout tests.
 * Expected bytes verified against MetaWear-SDK-Cpp/test/test_ibeacon.py.
 */
class IBeaconTest {

    // ---- Module byte ----

    @Test fun module_byte() =
        assertEquals(0x07, IBeacon.Enable().commandData[0].toInt() and 0xFF)

    // ---- Enable / Disable (register 0x01) ----
    // C++ test_enable:  [0x07, 0x01, 0x01]
    // C++ test_disable: [0x07, 0x01, 0x00]

    @Test fun enable_correctBytes() =
        assertArrayEquals(bytes(0x07, 0x01, 0x01), IBeacon.Enable().commandData)

    @Test fun disable_correctBytes() =
        assertArrayEquals(bytes(0x07, 0x01, 0x00), IBeacon.Disable().commandData)

    // ---- UUID (register 0x02) ----
    //
    // Per `ibeacon.h`: "ad_uuid — Byte representation of the UUID in little
    // endian ordering". The firmware expects the UUID on the wire with its
    // bytes reversed from the canonical (standard) form; it flips them back
    // when broadcasting over-the-air.

    @Test fun setUuid_length() {
        // [module(1), register(1), uuid(16)] = 18 bytes
        val cmd = IBeacon.SetUuid(UUID.randomUUID())
        assertEquals(18, cmd.commandData.size)
        assertEquals(0x07, cmd.commandData[0].toInt() and 0xFF)
        assertEquals(0x02, cmd.commandData[1].toInt() and 0xFF)
    }

    // Canonical bytes of 12345678-1234-1234-1234-123456789ABC are
    //   [0x12, 0x34, 0x56, 0x78, 0x12, 0x34, 0x12, 0x34,
    //    0x12, 0x34, 0x12, 0x34, 0x56, 0x78, 0x9A, 0xBC]
    // Reversed (wire order):
    //   [0xBC, 0x9A, 0x78, 0x56, 0x34, 0x12, 0x34, 0x12,
    //    0x34, 0x12, 0x34, 0x12, 0x78, 0x56, 0x34, 0x12]
    @Test fun setUuid_reversesBytes() {
        val uuid = UUID.fromString("12345678-1234-1234-1234-123456789ABC")
        val data = IBeacon.SetUuid(uuid).commandData
        // First wire byte is the last byte of the canonical UUID.
        assertEquals(0xBC, data[2].toInt() and 0xFF)
        assertEquals(0x9A, data[3].toInt() and 0xFF)
        assertEquals(0x78, data[4].toInt() and 0xFF)
        assertEquals(0x56, data[5].toInt() and 0xFF)
        // Last wire byte is the first byte of the canonical UUID.
        assertEquals(0x12, data[17].toInt() and 0xFF)
    }

    // Python `test_set_uuid` — exact firmware wire vector.
    //   UUID(326a9006-85cb-9195-d9dd-464cfbbae75a).bytes[::-1]
    //   → [0x5a, 0xe7, 0xba, 0xfb, 0x4c, 0x46, 0xdd, 0xd9,
    //      0x95, 0x91, 0xcb, 0x85, 0x06, 0x90, 0x6a, 0x32]
    @Test fun setUuid_pythonVector() {
        val uuid = UUID.fromString("326A9006-85CB-9195-D9DD-464CFBBAE75A")
        assertArrayEquals(
            bytes(
                0x07, 0x02,
                0x5A, 0xE7, 0xBA, 0xFB, 0x4C, 0x46, 0xDD, 0xD9,
                0x95, 0x91, 0xCB, 0x85, 0x06, 0x90, 0x6A, 0x32,
            ),
            IBeacon.SetUuid(uuid).commandData,
        )
    }

    // Sanity: feeding the UUID through the command and reversing the wire
    // payload should recover the canonical UUID bytes.
    @Test fun setUuid_roundTripsCanonicalForm() {
        val uuid = UUID.fromString("326A9006-85CB-9195-D9DD-464CFBBAE75A")
        val payload = IBeacon.SetUuid(uuid).commandData.copyOfRange(2, 18) // 16 wire bytes
        assertArrayEquals(canonicalBytes(uuid), payload.reversedArray())
    }

    // ---- Major (register 0x03) ----
    // C++ test_set_major: [0x07, 0x03, 0x4e, 0x00]  (major = 78 = 0x004E)

    @Test fun setMajor_cppReference() =
        // major = 78 → LE: [0x4E, 0x00]
        assertArrayEquals(bytes(0x07, 0x03, 0x4E, 0x00), IBeacon.SetMajor(78).commandData)

    @Test fun setMajor_littleEndian() =
        // 0x1234 → LE: [0x34, 0x12]
        assertArrayEquals(bytes(0x07, 0x03, 0x34, 0x12), IBeacon.SetMajor(0x1234).commandData)

    @Test fun setMajor_zero() =
        assertArrayEquals(bytes(0x07, 0x03, 0x00, 0x00), IBeacon.SetMajor(0).commandData)

    @Test fun setMajor_max() =
        assertArrayEquals(bytes(0x07, 0x03, 0xFF, 0xFF), IBeacon.SetMajor(0xFFFF).commandData)

    // ---- Minor (register 0x04) ----
    // C++ test_set_minor: [0x07, 0x04, 0x1d, 0x1d]  (minor = 0x1D1D = 7453)

    @Test fun setMinor_cppReference() =
        assertArrayEquals(bytes(0x07, 0x04, 0x1D, 0x1D), IBeacon.SetMinor(0x1D1D).commandData)

    @Test fun setMinor_littleEndian() =
        // 0xABCD → LE: [0xCD, 0xAB]
        assertArrayEquals(bytes(0x07, 0x04, 0xCD, 0xAB), IBeacon.SetMinor(0xABCD).commandData)

    // ---- RX Power (register 0x05) ----
    // C++ test_set_rx_power: [0x07, 0x05, 0xc9]  (–55 dBm as Int8 → 0xC9)

    @Test fun setRxPower_cppReference() =
        // –55 as an unsigned wire byte = 0xC9
        assertArrayEquals(bytes(0x07, 0x05, 0xC9), IBeacon.SetRxPower(-55).commandData)

    @Test fun setRxPower_register() =
        assertEquals(0x05, IBeacon.SetRxPower(-55).commandData[1].toInt() and 0xFF)

    @Test fun setRxPower_default_isMinusFiftyFive() =
        assertEquals(0xC9, IBeacon.SetRxPower().commandData[2].toInt() and 0xFF)

    // ---- TX Power (register 0x06) ----
    // C++ test_set_tx_power: [0x07, 0x06, 0xf4]  (–12 dBm as Int8 → 0xF4)

    @Test fun setTxPower_cppReference() =
        // –12 as an unsigned wire byte = 0xF4
        assertArrayEquals(bytes(0x07, 0x06, 0xF4), IBeacon.SetTxPower(-12).commandData)

    @Test fun setTxPower_register() =
        assertEquals(0x06, IBeacon.SetTxPower(0).commandData[1].toInt() and 0xFF)

    @Test fun setTxPower_zero() =
        assertArrayEquals(bytes(0x07, 0x06, 0x00), IBeacon.SetTxPower(0).commandData)

    @Test fun setTxPower_negative() =
        // –4 as an unsigned wire byte = 0xFC
        assertArrayEquals(bytes(0x07, 0x06, 0xFC), IBeacon.SetTxPower(-4).commandData)

    @Test fun setTxPower_default_isZero() =
        assertEquals(0x00, IBeacon.SetTxPower().commandData[2].toInt() and 0xFF)

    // ---- Period (register 0x07) ----
    // C++ test_set_period: [0x07, 0x07, 0xb3, 0x3a]  (period = 0x3AB3 = 15027 ms)

    @Test fun setPeriod_cppReference() =
        // 0x3AB3 = 15027 → LE: [0xB3, 0x3A]
        assertArrayEquals(bytes(0x07, 0x07, 0xB3, 0x3A), IBeacon.SetPeriod(0x3AB3).commandData)

    @Test fun setPeriod_700ms() =
        // 700 = 0x02BC → LE: [0xBC, 0x02]
        assertArrayEquals(bytes(0x07, 0x07, 0xBC, 0x02), IBeacon.SetPeriod(700).commandData)

    @Test fun setPeriod_default_is700() {
        val data = IBeacon.SetPeriod().commandData
        val period = (data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8)
        assertEquals(700, period)
    }

    @Test fun setPeriod_register() =
        assertEquals(0x07, IBeacon.SetPeriod(700).commandData[1].toInt() and 0xFF)

    @Test fun setPeriod_littleEndian() {
        // 0x0102 = 258ms → LE: [0x02, 0x01]
        val data = IBeacon.SetPeriod(0x0102).commandData
        assertEquals(0x02, data[2].toInt() and 0xFF)
        assertEquals(0x01, data[3].toInt() and 0xFF)
    }

    // ---- helpers ----

    /** Canonical (big-endian / standard display order) bytes of a UUID. */
    private fun canonicalBytes(uuid: UUID): ByteArray {
        val out = ByteArray(16)
        var msb = uuid.mostSignificantBits
        var lsb = uuid.leastSignificantBits
        for (i in 7 downTo 0) {
            out[i] = (msb and 0xFF).toByte()
            msb = msb ushr 8
        }
        for (i in 15 downTo 8) {
            out[i] = (lsb and 0xFF).toByte()
            lsb = lsb ushr 8
        }
        return out
    }
}
