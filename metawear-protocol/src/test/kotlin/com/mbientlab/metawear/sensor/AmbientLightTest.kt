package com.mbientlab.metawear.sensor

import com.mbientlab.metawear.bytes
import com.mbientlab.metawear.model.MetaWearException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

// Ambient Light (LTR329) Python-vector
// byte-layout tests. Expected bytes verified against
// MetaWear-SDK-Cpp/test/test_ambientlight_ltr329.py.

class AmbientLightCommandTest {

    // ---- Module byte ----

    @Test fun module_byte_is0x14() =
        assertEquals(0x14, AmbientLight().startCommand[0].toInt() and 0xFF)

    // ---- Enable / Disable (register 0x01) ----

    @Test fun startCommand_bytes() =
        assertArrayEquals(bytes(0x14, 0x01, 0x01), AmbientLight().startCommand)

    @Test fun stopCommand_bytes() =
        assertArrayEquals(bytes(0x14, 0x01, 0x00), AmbientLight().stopCommand)

    // ---- CONFIG (register 0x02) — Python reference vectors ----

    // test_set_gain:
    //   gain = 48X, everything else default → [0x14, 0x02, 0x18, 0x03]
    //   48X maps to als_gain=6 in the bitfield (+2 offset vs enum),
    //   byte0 = 6 << 2 = 0x18;
    //   default rate = 500ms (3), default integration = 100ms (0) → byte1 = 3 | 0 = 0x03.
    @Test fun configBytes_gain48X_defaults() {
        val als = AmbientLight(gain = AmbientLight.Gain.X48)
        assertEquals(1, als.configureCommands.size)
        assertArrayEquals(bytes(0x14, 0x02, 0x18, 0x03), als.configureCommands[0])
    }

    // test_set_integration_time:
    //   integration_time = 400ms (enum 3), defaults otherwise → [0x14, 0x02, 0x00, 0x1B]
    //   byte1 = rate(3) | (IT(3) << 3) = 3 | 24 = 0x1B.
    @Test fun configBytes_integrationTime_400ms_defaults() {
        val als = AmbientLight(integrationTime = AmbientLight.IntegrationTime.MS400)
        assertArrayEquals(bytes(0x14, 0x02, 0x00, 0x1B), als.configureCommands[0])
    }

    // test_set_measurement_rate:
    //   measurement_rate = 2000ms (enum 5), defaults otherwise → [0x14, 0x02, 0x00, 0x05]
    //   byte1 = rate(5) | (IT(0) << 3) = 0x05.
    @Test fun configBytes_measurementRate_2000ms_defaults() {
        val als = AmbientLight(measurementRate = AmbientLight.MeasurementRate.MS2000)
        assertArrayEquals(bytes(0x14, 0x02, 0x00, 0x05), als.configureCommands[0])
    }

    // test_set_all_config:
    //   gain=8X, integration=250ms, rate=50ms → [0x14, 0x02, 0x0C, 0x28]
    //   byte0 = gain=3 << 2 = 0x0C;
    //   byte1 = rate(0) | (IT(5) << 3) = 0x28.
    @Test fun configBytes_allCustom() {
        val als = AmbientLight(
            gain = AmbientLight.Gain.X8,
            integrationTime = AmbientLight.IntegrationTime.MS250,
            measurementRate = AmbientLight.MeasurementRate.MS50,
        )
        assertArrayEquals(bytes(0x14, 0x02, 0x0C, 0x28), als.configureCommands[0])
    }

    // ---- Standalone write-config command ----

    @Test fun writeConfigCommand_matchesStreamConfig() {
        val als = AmbientLight(gain = AmbientLight.Gain.X48)
        val cmd = AmbientLightWriteConfig(als)
        assertArrayEquals(bytes(0x14, 0x02, 0x18, 0x03), cmd.commandData)
    }

    // ---- Gain encoding sanity ----

    @Test fun gainRegisterValue_matchesC() {
        assertEquals(0, AmbientLight.Gain.X1.registerValue)
        assertEquals(1, AmbientLight.Gain.X2.registerValue)
        assertEquals(2, AmbientLight.Gain.X4.registerValue)
        assertEquals(3, AmbientLight.Gain.X8.registerValue)
        assertEquals(6, AmbientLight.Gain.X48.registerValue)  // +2 offset per C++
        assertEquals(7, AmbientLight.Gain.X96.registerValue)
    }

    @Test fun configByte0_usesGainRegisterValue() =
        assertEquals(7 shl 2, AmbientLight(gain = AmbientLight.Gain.X96).configByte0)  // 0x1C
}

class AmbientLightDataHandlerTest {

    // ---- Subscribe / unsubscribe ----

    @Test fun subscribeRegister_is0x03() = assertEquals(0x03, AmbientLight().dataRegister)

    @Test fun packedDataRegister_isNull() = assertNull(AmbientLight().packedDataRegister)

    // ---- Parse illuminance — Python reference vector ----

    // test_get_illuminance_data: b'\x14\x03\xed\x92\xb0\x00' → 11571949 (raw UInt32).
    @Test fun parseSample_pythonVector() {
        val packet = bytes(0x14, 0x03, 0xED, 0x92, 0xB0, 0x00)
        assertEquals(11_571_949L, AmbientLight().parseSample(packet))
    }

    // Illuminance is UInt32 little-endian at offset 2.
    @Test fun parseSample_littleEndian() {
        val packet = bytes(0x14, 0x03, 0x01, 0x02, 0x03, 0x04)
        assertEquals(0x04030201L, AmbientLight().parseSample(packet))
    }

    @Test fun parseSample_tooShort_throws() {
        val packet = bytes(0x14, 0x03, 0x01)
        assertThrows(MetaWearException.OperationFailed::class.java) {
            AmbientLight().parseSample(packet)
        }
    }

    // ---- Lux conversion ----

    @Test fun lux_convertsMilliLuxToLux() {
        assertEquals(11571.949f, AmbientLight.lux(11_571_949L))
        assertEquals(0.0f, AmbientLight.lux(0L))
        assertEquals(1.0f, AmbientLight.lux(1000L))
    }
}
