package com.mbientlab.metawear.protocol

import com.mbientlab.metawear.bytes
import com.mbientlab.metawear.floatLE
import com.mbientlab.metawear.model.MetaWearException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Packet-parser wire-format correctness, no hardware. */
class PacketParserTest {

    // ---- Primitives ----

    @Test fun parseInt16_positive() = assertEquals(16384, PacketParser.parseInt16LE(bytes(0x00, 0x40), 0))
    @Test fun parseInt16_negative() = assertEquals(-1, PacketParser.parseInt16LE(bytes(0xFF, 0xFF), 0))
    @Test fun parseInt16_min() = assertEquals(-32768, PacketParser.parseInt16LE(bytes(0x00, 0x80), 0))
    @Test fun parseInt16_max() = assertEquals(32767, PacketParser.parseInt16LE(bytes(0xFF, 0x7F), 0))
    @Test fun parseUInt32_known() = assertEquals(65536L, PacketParser.parseUInt32LE(bytes(0x00, 0x00, 0x01, 0x00), 0))
    @Test fun parseFloat32_one() = assertEquals(1.0f, PacketParser.parseFloat32LE(bytes(0x00, 0x00, 0x80, 0x3F), 0))
    @Test fun parseFloat32_negative() = assertEquals(-1.0f, PacketParser.parseFloat32LE(bytes(0x00, 0x00, 0x80, 0xBF), 0))

    // ---- Accelerometer ----
    // x = 0x4000 = 16384 → 1.0g ; y = 0 → 0.0g ; z = 0x2000 = 8192 → 0.5g
    private val accPacket = bytes(0x03, 0x04, 0x00, 0x40, 0x00, 0x00, 0x00, 0x20)
    private val accScale = 16384f

    @Test fun parsesXYZ() {
        val s = PacketParser.parseCartesianFloat(accPacket, accScale)
        assertEquals(1.0f, s.x)
        assertEquals(0.0f, s.y)
        assertEquals(0.5f, s.z)
    }

    @Test fun parsesNegativeAxis() {
        // x = 0xC000 = -16384 → -1.0g
        val s = PacketParser.parseCartesianFloat(bytes(0x03, 0x04, 0x00, 0xC0, 0x00, 0x00, 0x00, 0x00), accScale)
        assertEquals(-1.0f, s.x)
    }

    @Test fun tooShortThrows() {
        assertThrows(MetaWearException::class.java) {
            PacketParser.parseCartesianFloat(bytes(0x03, 0x04, 0x00), accScale)
        }
    }

    @Test fun parsesPacked_threeSamples() {
        val packed = bytes(
            0x03, 0x1C,
            0x00, 0x40, 0x00, 0x00, 0x00, 0x00, // sample 0: x = 1g
            0x00, 0x00, 0x00, 0x40, 0x00, 0x00, // sample 1: y = 1g
            0x00, 0x00, 0x00, 0x00, 0x00, 0x40, // sample 2: z = 1g
        )
        val s = PacketParser.parsePackedCartesianFloat(packed, accScale)
        assertEquals(3, s.size)
        assertEquals(1.0f, s[0].x); assertEquals(0.0f, s[0].y); assertEquals(0.0f, s[0].z)
        assertEquals(0.0f, s[1].x); assertEquals(1.0f, s[1].y); assertEquals(0.0f, s[1].z)
        assertEquals(0.0f, s[2].x); assertEquals(0.0f, s[2].y); assertEquals(1.0f, s[2].z)
    }

    // ---- Sensor fusion ----

    @Test fun parsesQuaternion() {
        val packet = bytes(0x19, 0x07) +
            floatLE(1.0f) + floatLE(0.0f) + floatLE(0.0f) + floatLE(0.0f)
        val q = PacketParser.parseQuaternion(packet)
        assertEquals(1.0f, q.w); assertEquals(0.0f, q.x); assertEquals(0.0f, q.y); assertEquals(0.0f, q.z)
    }

    @Test fun parsesEulerAngles() {
        val packet = bytes(0x19, 0x08) +
            floatLE(90.0f) + floatLE(0.0f) + floatLE(45.0f) + floatLE(0.0f)
        val e = PacketParser.parseEulerAngles(packet)
        assertEquals(90.0f, e.heading); assertEquals(0.0f, e.pitch); assertEquals(45.0f, e.roll); assertEquals(0.0f, e.yaw)
    }

    @Test fun parsesGravityVector() {
        val packet = bytes(0x19, 0x09) + floatLE(0.0f) + floatLE(0.0f) + floatLE(9.80665f)
        val g = PacketParser.parseGravityVector(packet)
        assertEquals(1.0f, g.z, 0.0001f)
    }

    // ---- Scalar sensors ----

    @Test fun parsesPressure() =
        assertEquals(101.0f, PacketParser.parsePressure(bytes(0x12, 0x01, 0x00, 0x65, 0x00, 0x00)))

    @Test fun parsesAltitude() =
        assertEquals(100.0f, PacketParser.parseAltitude(bytes(0x12, 0x02, 0x00, 0x64, 0x00, 0x00)))

    @Test fun parsesNegativeAltitude() =
        assertEquals(-1.0f, PacketParser.parseAltitude(bytes(0x12, 0x02, 0x00, 0xFF, 0xFF, 0xFF)))

    @Test fun parsesTemperature() =
        assertEquals(25.0f, PacketParser.parseTemperature(bytes(0x04, 0x81, 0x00, 0xC8, 0x00)))

    @Test fun parsesNegativeTemperature() =
        assertEquals(-10.0f, PacketParser.parseTemperature(bytes(0x04, 0x81, 0x00, 0xB0, 0xFF)))

    @Test fun parsesBattery() {
        val b = PacketParser.parseBatteryState(bytes(0x11, 0x91, 0x55, 0x9C, 0x0F))
        assertEquals(85, b.charge)
        assertEquals(3996, b.voltage)
    }

    // ---- Log entry ----

    @Test fun parsesIdAndResetUID() {
        // Byte 0 = 0x63 = 0b01100011 → id = 0x03, resetUID = 0x03
        val e = PacketParser.parseLogEntry(bytes(0x63, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        assertEquals(3, e.id)
        assertEquals(3, e.resetUID)
    }

    @Test fun parsesTick() {
        val e = PacketParser.parseLogEntry(bytes(0x00, 0x67, 0x45, 0x23, 0x01, 0x00, 0x00, 0x00, 0x00))
        assertEquals(0x01234567L, e.tick)
    }

    @Test fun parsesFullTickRange() {
        // Hardware-observed entry from a 1 Hz euler download.
        val e = PacketParser.parseLogEntry(bytes(0xC0, 0x53, 0x9F, 0x3B, 0x00, 0xA4, 0xFD, 0xB3, 0x43))
        assertEquals(0x00, e.id)             // 0xC0 & 0x1F
        assertEquals(0x06, e.resetUID)       // (0xC0 >> 5) & 0x07
        assertEquals(0x003B9F53L, e.tick)
        assertEquals(0x43B3FDA4L, e.rawData)
    }

    @Test fun parsesData() {
        val e = PacketParser.parseLogEntry(bytes(0x00, 0x00, 0x00, 0x00, 0x00, 0xEF, 0xBE, 0xAD, 0xDE))
        assertEquals(0xDEADBEEFL, e.rawData)
    }

    @Test fun rejectsShortPacket() {
        assertThrows(MetaWearException::class.java) {
            PacketParser.parseLogEntry(bytes(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        }
    }

    @Test fun msPerTickIsCorrect() {
        assertEquals((48.0 / 32768.0) * 1000.0, PacketParser.MS_PER_TICK, 0.000001)
    }
}
