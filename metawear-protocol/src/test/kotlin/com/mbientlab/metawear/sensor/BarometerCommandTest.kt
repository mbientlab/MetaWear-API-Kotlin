package com.mbientlab.metawear.sensor

import com.mbientlab.metawear.bytes
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// Barometer command byte-layout tests. Reference vectors
// from MetaWear-SDK-Cpp/test/test_barometer_bmp280.py and
// barometer_bosch_base.py. All bytes verified against the C++ `BoschBaroConfig`
// bitfield in barometer_bosch.cpp.

class BarometerCommandTest {

    @Test fun start_stop() {
        val b = Barometer()
        assertArrayEquals(bytes(0x12, 0x04, 0x01, 0x01), b.startCommand)
        assertArrayEquals(bytes(0x12, 0x04, 0x00, 0x00), b.stopCommand)
    }

    // ---- Oversampling — Python test_set_oversampling ----

    @Test fun config_oversampling_ultraLowPower() {
        val b = Barometer(Barometer.Oversampling.ULTRA_LOW_POWER, Barometer.IirFilter.OFF, Barometer.BmpStandbyTime.MS0_5)
        // byte0 = 0x24: pressure=1 (bits 2-4), temp=ULTRA_LOW_POWER=1 (bits 5-7)
        assertArrayEquals(bytes(0x12, 0x03, 0x24, 0x00), b.configureCommands[0])
    }

    @Test fun config_oversampling_lowPower() {
        val b = Barometer(Barometer.Oversampling.LOW_POWER, Barometer.IirFilter.OFF, Barometer.BmpStandbyTime.MS0_5)
        assertArrayEquals(bytes(0x12, 0x03, 0x28, 0x00), b.configureCommands[0])
    }

    @Test fun config_oversampling_standard() {
        val b = Barometer(Barometer.Oversampling.STANDARD, Barometer.IirFilter.OFF, Barometer.BmpStandbyTime.MS0_5)
        assertArrayEquals(bytes(0x12, 0x03, 0x2C, 0x00), b.configureCommands[0])
    }

    @Test fun config_oversampling_high() {
        val b = Barometer(Barometer.Oversampling.HIGH, Barometer.IirFilter.OFF, Barometer.BmpStandbyTime.MS0_5)
        assertArrayEquals(bytes(0x12, 0x03, 0x30, 0x00), b.configureCommands[0])
    }

    @Test fun config_oversampling_ultraHigh_bumpsTemperatureToLowPower() {
        // ULTRA_HIGH pressure forces temperature oversampling to LOW_POWER (2).
        // byte0 = 0x54: pressure=5 (bits 2-4), temp=2 (bits 5-7)
        val b = Barometer(Barometer.Oversampling.ULTRA_HIGH, Barometer.IirFilter.OFF, Barometer.BmpStandbyTime.MS0_5)
        assertArrayEquals(bytes(0x12, 0x03, 0x54, 0x00), b.configureCommands[0])
    }

    // ---- IIR filter — Python test_set_filter ----
    // All filter tests use default oversampling=standard, so byte0 == 0x2C.

    @Test fun config_filter_off() {
        val b = Barometer(Barometer.Oversampling.STANDARD, Barometer.IirFilter.OFF, Barometer.BmpStandbyTime.MS0_5)
        assertArrayEquals(bytes(0x12, 0x03, 0x2C, 0x00), b.configureCommands[0])
    }

    @Test fun config_filter_avg2() {
        val b = Barometer(Barometer.Oversampling.STANDARD, Barometer.IirFilter.AVG2, Barometer.BmpStandbyTime.MS0_5)
        assertArrayEquals(bytes(0x12, 0x03, 0x2C, 0x04), b.configureCommands[0])
    }

    @Test fun config_filter_avg4() {
        val b = Barometer(Barometer.Oversampling.STANDARD, Barometer.IirFilter.AVG4, Barometer.BmpStandbyTime.MS0_5)
        assertArrayEquals(bytes(0x12, 0x03, 0x2C, 0x08), b.configureCommands[0])
    }

    @Test fun config_filter_avg8() {
        val b = Barometer(Barometer.Oversampling.STANDARD, Barometer.IirFilter.AVG8, Barometer.BmpStandbyTime.MS0_5)
        assertArrayEquals(bytes(0x12, 0x03, 0x2C, 0x0C), b.configureCommands[0])
    }

    @Test fun config_filter_avg16() {
        val b = Barometer(Barometer.Oversampling.STANDARD, Barometer.IirFilter.AVG16, Barometer.BmpStandbyTime.MS0_5)
        assertArrayEquals(bytes(0x12, 0x03, 0x2C, 0x10), b.configureCommands[0])
    }

    // ---- BMP280 standby matrix — Python test_set_standby (BMP280) ----

    @Test fun config_bmp_standby_matrix() {
        val expected = listOf(
            Barometer.BmpStandbyTime.MS0_5 to 0x00,
            Barometer.BmpStandbyTime.MS62_5 to 0x20,
            Barometer.BmpStandbyTime.MS125 to 0x40,
            Barometer.BmpStandbyTime.MS250 to 0x60,
            Barometer.BmpStandbyTime.MS500 to 0x80,
            Barometer.BmpStandbyTime.MS1000 to 0xA0,
            Barometer.BmpStandbyTime.MS2000 to 0xC0,
            Barometer.BmpStandbyTime.MS4000 to 0xE0,
        )
        for ((standby, byte1) in expected) {
            val b = Barometer(Barometer.Oversampling.STANDARD, Barometer.IirFilter.OFF, standby)
            assertArrayEquals(
                bytes(0x12, 0x03, 0x2C, byte1),
                b.configureCommands[0],
            ) { "standby $standby should encode byte1 = ${byte1.toString(16)}" }
        }
    }

    // ---- Composite — Python test_set_all_config ----
    // LOW_POWER + AVG_16 + 500ms → [0x12, 0x03, 0x28, 0x90]

    @Test fun config_composite_lowPower_avg16_500ms() {
        val b = Barometer(Barometer.Oversampling.LOW_POWER, Barometer.IirFilter.AVG16, Barometer.BmpStandbyTime.MS500)
        assertArrayEquals(bytes(0x12, 0x03, 0x28, 0x90), b.configureCommands[0])
    }

    // ---- BME280 — same encoding, different interpretation at indices 6/7 ----

    @Test fun config_bme_standby_10ms_encodesAs6() {
        val b = Barometer(Barometer.Oversampling.STANDARD, Barometer.IirFilter.OFF, Barometer.BmeStandbyTime.MS10)
        // BME raw 6 << 5 = 0xC0
        assertArrayEquals(bytes(0x12, 0x03, 0x2C, 0xC0), b.configureCommands[0])
        assertEquals(Barometer.Variant.BME280, b.variant)
    }

    @Test fun config_bme_standby_20ms_encodesAs7() {
        val b = Barometer(Barometer.Oversampling.STANDARD, Barometer.IirFilter.OFF, Barometer.BmeStandbyTime.MS20)
        assertArrayEquals(bytes(0x12, 0x03, 0x2C, 0xE0), b.configureCommands[0])
        assertEquals(Barometer.Variant.BME280, b.variant)
    }

    // ---- Registers ----

    @Test fun pressure_register() = assertEquals(0x01, Barometer().dataRegister)

    @Test fun altitude_register() = assertEquals(0x02, Altimeter().dataRegister)

    @Test fun altimeter_reusesBarometerConfig() {
        val baro = Barometer(Barometer.Oversampling.HIGH, Barometer.IirFilter.AVG8, Barometer.BmpStandbyTime.MS250)
        val alt = Altimeter(baro)
        assertEquals(baro.configureCommands.size, alt.configureCommands.size)
        for (i in baro.configureCommands.indices) {
            assertArrayEquals(baro.configureCommands[i], alt.configureCommands[i])
        }
        assertArrayEquals(baro.startCommand, alt.startCommand)
        assertArrayEquals(baro.stopCommand, alt.stopCommand)
    }

    // ---- Variant raw values — match C++ MBL_MW_MODULE_BARO_TYPE_* ----

    @Test fun variant_rawValues() {
        assertEquals(0, Barometer.Variant.BMP280.raw)
        assertEquals(1, Barometer.Variant.BME280.raw)
    }

    // ---- One-shot pressure read ----

    @Test fun pressureRead_command() {
        // Register 0x01 with READ bit → 0x81
        assertArrayEquals(bytes(0x12, 0x81), BarometerPressureRead().readCommand)
    }
}

// Barometer data-handler tests.
// Reference vectors from MetaWear-SDK-Cpp/test/barometer_bosch_base.py.

class BarometerDataHandlerTest {

    @Test fun pressure_parse_referenceVector() {
        // b'\x12\x01\xd3\x35\x8b\x01' → 101173.828125 Pa
        val packet = bytes(0x12, 0x01, 0xD3, 0x35, 0x8B, 0x01)
        assertEquals(101173.828125f, Barometer().parseSample(packet), 0.001f)
    }

    @Test fun altitude_parse_referenceVector() {
        // b'\x12\x02\x1e\x1f\xfe\xff' → -480.8828125 m
        val packet = bytes(0x12, 0x02, 0x1E, 0x1F, 0xFE, 0xFF)
        assertEquals(-480.8828125f, Altimeter().parseSample(packet), 0.001f)
    }

    @Test fun pressureRead_parse_sameAsStream() {
        // One-shot read uses the same packet shape; divide by 256.
        val packet = bytes(0x12, 0x01, 0xD3, 0x35, 0x8B, 0x01)
        assertEquals(101173.828125f, BarometerPressureRead().parseSample(packet), 0.001f)
    }
}
