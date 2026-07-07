package com.mbientlab.metawear.sensor

import com.mbientlab.metawear.bytes
import com.mbientlab.metawear.model.MetaWearException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Temperature / thermometer suites
// (the debug-module suites live elsewhere). Reference vectors from
// MetaWear-SDK-Cpp/test/test_multichanneltemperature.py.

class TemperatureChannelTest {

    @Test fun readCommand_channel0() {
        // [0x04, 0x81, 0x00] — module=0x04, register=0x01|0x80=0x81, channel=0
        assertArrayEquals(bytes(0x04, 0x81, 0x00), TemperatureChannel(0).readCommand)
    }

    @Test fun readCommand_channel2() =
        assertArrayEquals(bytes(0x04, 0x81, 0x02), TemperatureChannel(2).readCommand)

    @Test fun nrf_isChannel0() {
        assertEquals(0, TemperatureChannel.NRF.channel)
        assertArrayEquals(bytes(0x04, 0x81, 0x00), TemperatureChannel.NRF.readCommand)
    }

    @Test fun bosch_isChannel2() = assertEquals(2, TemperatureChannel.BOSCH.channel)

    @Test fun bmp280_isChannel3() = assertEquals(3, TemperatureChannel.BMP280.channel)

    @Test fun readCommand_hasReadBit() {
        // Byte 1 must have bit 7 set (read bit)
        val cmd = TemperatureChannel(1).readCommand
        assertTrue((cmd[1].toInt() and 0x80) != 0)
    }

    @Test fun silentReadCommand_hasReadAndSilentBits() {
        // 0xC1 = 0x80 (read) | 0x40 (silent) | 0x01 (register)
        assertArrayEquals(bytes(0x04, 0xC1, 0x00), TemperatureChannel(0).silentReadCommand)
    }
}

class MultiChannelTemperatureCommandTest {

    // Python: test_read_temperature (MetaWear R) — channels 0 and 1.
    @Test fun readCommand_metawearR_onDie() =
        assertArrayEquals(bytes(0x04, 0x81, 0x00), Thermometer(channel = 0).readCommand)

    @Test fun readCommand_metawearR_extThermistor() =
        assertArrayEquals(bytes(0x04, 0x81, 0x01), Thermometer(channel = 1).readCommand)

    // Python: test_read_temperature (RPro) — all 4 channels.
    @Test fun readCommand_rpro_allChannels() {
        for (ch in 0..3) {
            assertArrayEquals(bytes(0x04, 0x81, ch), Thermometer(channel = ch).readCommand)
        }
    }

    // Python: test_read_temperature_silent — silent bit 0x40 sets 0x81 → 0xC1.
    @Test fun silentReadCommand_rpro_allChannels() {
        for (ch in 0..3) {
            assertArrayEquals(
                bytes(0x04, 0xC1, ch),
                Thermometer(channel = ch, silent = true).readCommand,
            )
        }
    }

    @Test fun dataRegister_is1() = assertEquals(0x01, Thermometer(channel = 0).dataRegister)

    // Python: test_configure_ext_thermistor (MetaWear R, channel 1)
    // expected = [0x04, 0x02, 0x01, 0x00, 0x01, 0x00]
    @Test fun configureExt_metawearR() {
        val cmd = ThermometerConfigureExt(channel = 1, dataPin = 0, pulldownPin = 1, activeHigh = false)
        assertArrayEquals(bytes(0x04, 0x02, 0x01, 0x00, 0x01, 0x00), cmd.commandData)
    }

    // Python: test_configure_ext_thermistor (RPro, channel 2)
    // expected = [0x04, 0x02, 0x02, 0x00, 0x01, 0x00]
    @Test fun configureExt_rpro() {
        val cmd = ThermometerConfigureExt(channel = 2, dataPin = 0, pulldownPin = 1, activeHigh = false)
        assertArrayEquals(bytes(0x04, 0x02, 0x02, 0x00, 0x01, 0x00), cmd.commandData)
    }

    @Test fun configureExt_activeHighEncodesAs1() {
        val cmd = ThermometerConfigureExt(channel = 2, dataPin = 3, pulldownPin = 4, activeHigh = true)
        assertArrayEquals(bytes(0x04, 0x02, 0x02, 0x03, 0x04, 0x01), cmd.commandData)
    }

    // ---- Source enum raw values — match C++ MBL_MW_TEMPERATURE_SOURCE_* ----

    @Test fun source_rawValues() {
        assertEquals(-1, ThermometerSource.INVALID.raw)
        assertEquals(0, ThermometerSource.NRF_DIE.raw)
        assertEquals(1, ThermometerSource.EXT_THERMISTOR.raw)
        assertEquals(2, ThermometerSource.BMP280.raw)
        assertEquals(3, ThermometerSource.PRESET_THERMISTOR.raw)
    }
}

class MultiChannelTemperatureDataHandlerTest {

    // Python TestMultiChannelTemperatureMwr.test_get_temperature_data

    @Test fun parse_mwR_onDie_32C() {
        // b'\x04\x81\x00\x00\x01' → 32.0 (raw 256 / 8)
        val packet = bytes(0x04, 0x81, 0x00, 0x00, 0x01)
        assertEquals(32.0f, Thermometer(channel = 0).parseSample(packet))
    }

    @Test fun parse_mwR_extThermistor_21_5C() {
        // b'\x04\x81\x01\xac\x00' → 21.5 (raw 172 / 8)
        val packet = bytes(0x04, 0x81, 0x01, 0xAC, 0x00)
        assertEquals(21.5f, Thermometer(channel = 1).parseSample(packet))
    }

    // Python TestMultiChannelTemperatureMwrPro.test_get_temperature_data

    @Test fun parse_rpro_onDie_31_75C() {
        // b'\x04\x81\x00\xfe\x00' → 31.75 (raw 254 / 8)
        val packet = bytes(0x04, 0x81, 0x00, 0xFE, 0x00)
        assertEquals(31.75f, Thermometer(channel = 0).parseSample(packet))
    }

    @Test fun parse_rpro_preset_21C() {
        // b'\x04\x81\x01\xa8\x00' → 21.00 (raw 168 / 8)
        val packet = bytes(0x04, 0x81, 0x01, 0xA8, 0x00)
        assertEquals(21.00f, Thermometer(channel = 1).parseSample(packet))
    }

    @Test fun parse_rpro_ext_negative10_5C() {
        // b'\x04\x81\x02\xac\xff' → -10.5 (raw int16 = -84, /8 = -10.5)
        val packet = bytes(0x04, 0x81, 0x02, 0xAC, 0xFF)
        assertEquals(-10.5f, Thermometer(channel = 2).parseSample(packet))
    }

    @Test fun parse_rpro_bmp280_0C() {
        // b'\x04\x81\x03\x00\x00' → 0
        val packet = bytes(0x04, 0x81, 0x03, 0x00, 0x00)
        assertEquals(0.0f, Thermometer(channel = 3).parseSample(packet))
    }

    @Test fun parse_shortPacket_throws() {
        // 4-byte packet (no channel byte) must be rejected.
        val packet = bytes(0x04, 0x81, 0xC8, 0x00)
        assertThrows(MetaWearException.OperationFailed::class.java) {
            Thermometer(channel = 0).parseSample(packet)
        }
    }
}
