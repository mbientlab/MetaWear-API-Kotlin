package com.mbientlab.metawear.sensor

import com.mbientlab.metawear.bytes
import com.mbientlab.metawear.model.MetaWearException
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.protocol.PacketParser
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

// Humidity (BME280) suites. Reference vectors
// from MetaWear-SDK-Cpp/test/backup/test_humidity_bme280.py.

class HumidityCommandTest {

    // ---- Module opcode ----

    @Test fun module_is0x16() = assertEquals(0x16, Module.HUMIDITY.value)

    // ---- Oversampling raw values ----

    // C++ `MblMwHumidityBme280Oversampling` — sequential 1..5
    @Test fun oversampling_rawValues() {
        assertEquals(1, Humidity.Oversampling.X1.raw)
        assertEquals(2, Humidity.Oversampling.X2.raw)
        assertEquals(3, Humidity.Oversampling.X4.raw)
        assertEquals(4, Humidity.Oversampling.X8.raw)
        assertEquals(5, Humidity.Oversampling.X16.raw)
    }

    // ---- setOversampling byte vectors — Python test_oversampling ----

    @Test fun setOversampling_x1() =
        assertArrayEquals(bytes(0x16, 0x02, 0x01), HumiditySetOversampling(Humidity.Oversampling.X1).commandData)

    @Test fun setOversampling_x2() =
        assertArrayEquals(bytes(0x16, 0x02, 0x02), HumiditySetOversampling(Humidity.Oversampling.X2).commandData)

    @Test fun setOversampling_x4() =
        assertArrayEquals(bytes(0x16, 0x02, 0x03), HumiditySetOversampling(Humidity.Oversampling.X4).commandData)

    @Test fun setOversampling_x8() =
        assertArrayEquals(bytes(0x16, 0x02, 0x04), HumiditySetOversampling(Humidity.Oversampling.X8).commandData)

    @Test fun setOversampling_x16() =
        assertArrayEquals(bytes(0x16, 0x02, 0x05), HumiditySetOversampling(Humidity.Oversampling.X16).commandData)

    // ---- Readable surface ----

    @Test fun readCommand_hasReadBit() =
        assertArrayEquals(bytes(0x16, 0x81), Humidity().readCommand)

    @Test fun module_dataRegister() {
        val reader = Humidity()
        assertEquals(Module.HUMIDITY, reader.module)
        assertEquals(0x01, reader.dataRegister)
        assertNull(reader.packedDataRegister)
    }
}

class HumidityParsingTest {

    // Python test_humidity_data — exact firmware response vector.
    // b'\x16\x81\xc7\xfc\x00\x00' → raw uint32 LE = 0x0000FCC7 = 64711 → 64711/1024 = 63.1943359375%
    @Test fun parse_pythonVector_63percent() {
        val packet = bytes(0x16, 0x81, 0xC7, 0xFC, 0x00, 0x00)
        assertEquals(63.1943359375f, Humidity().parseSample(packet))
    }

    // Same vector via the parser helper directly.
    @Test fun parseHumidity_helper() {
        val packet = bytes(0x16, 0x81, 0xC7, 0xFC, 0x00, 0x00)
        assertEquals(63.1943359375f, PacketParser.parseHumidity(packet))
    }

    // raw uint32 = 0x0000_C000 = 49152 → 49152/1024 = 48.0%
    @Test fun parse_48percent() =
        assertEquals(48.0f, Humidity().parseSample(bytes(0x16, 0x81, 0x00, 0xC0, 0x00, 0x00)))

    // raw uint32 = 0 → 0%
    @Test fun parse_zero() =
        assertEquals(0.0f, Humidity().parseSample(bytes(0x16, 0x81, 0x00, 0x00, 0x00, 0x00)))

    // raw uint32 = 0x0001_9000 = 102400 → 100.0%
    @Test fun parse_100percent() =
        assertEquals(100.0f, Humidity().parseSample(bytes(0x16, 0x81, 0x00, 0x90, 0x01, 0x00)))

    @Test fun parse_shortPacket_throws() {
        // 5-byte packet (missing one raw byte) must be rejected — the BME280
        // payload is a full UInt32, not truncated like temperature.
        val packet = bytes(0x16, 0x81, 0x00, 0x00, 0x00)
        assertThrows(MetaWearException.OperationFailed::class.java) {
            Humidity().parseSample(packet)
        }
    }
}
