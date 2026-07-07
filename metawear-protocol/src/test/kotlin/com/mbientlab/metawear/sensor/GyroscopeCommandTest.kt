package com.mbientlab.metawear.sensor

import com.mbientlab.metawear.bytes
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Gyroscope command byte-layout tests. */
class GyroscopeCommandTest {

    private val sensor = GyroscopeBmi160(GyroscopeBmi160.Odr.HZ100, GyroscopeBmi160.Range.DPS2000)

    @Test fun startCommand() = assertArrayEquals(bytes(0x13, 0x01, 0x01), sensor.startCommand)
    @Test fun stopCommand() = assertArrayEquals(bytes(0x13, 0x01, 0x00), sensor.stopCommand)
    @Test fun enableCommand() = assertArrayEquals(bytes(0x13, 0x02, 0x01, 0x00), sensor.enableCommand)
    @Test fun disableCommand() = assertArrayEquals(bytes(0x13, 0x02, 0x00, 0x01), sensor.disableCommand)

    @Test fun configCommand_odr100_2000dps() {
        // ODR 100Hz = raw 8, bwp = 2 → (2 << 4) | 8 = 0x28 ; range 2000dps = 0
        assertArrayEquals(bytes(0x13, 0x03, 0x28, 0x00), sensor.configureCommands[0])
    }

    @Test fun configCommand_odr200_500dps() {
        // ODR 200Hz = raw 9 → 0x29, range 500dps = 2
        val s = GyroscopeBmi160(GyroscopeBmi160.Odr.HZ200, GyroscopeBmi160.Range.DPS500)
        assertArrayEquals(bytes(0x13, 0x03, 0x29, 0x02), s.configureCommands[0])
    }

    @Test fun bmi160_dataRegister() = assertEquals(0x05, sensor.dataRegister)

    @Test fun bmi270_dataRegister_differs() {
        val bmi270 = GyroscopeBmi270(GyroscopeBmi270.Odr.HZ100, GyroscopeBmi270.Range.DPS2000)
        assertEquals(0x04, bmi270.dataRegister)
    }

    // --- Reference vectors from MetaWear-SDK-Cpp/test/test_gyro_bmi160.py ---

    @Test fun configCommand_odr200_default_range_matches_python() {
        // test_set_odr: set ODR = 200Hz with default range dps2000 → [0x13, 0x03, 0x29, 0x00]
        val s = GyroscopeBmi160(GyroscopeBmi160.Odr.HZ200, GyroscopeBmi160.Range.DPS2000)
        assertArrayEquals(bytes(0x13, 0x03, 0x29, 0x00), s.configureCommands[0])
    }

    @Test fun configCommand_default_odr_250dps_matches_python() {
        // test_set_fsr: default ODR = 100Hz, range = 250dps → [0x13, 0x03, 0x28, 0x03]
        val s = GyroscopeBmi160(GyroscopeBmi160.Odr.HZ100, GyroscopeBmi160.Range.DPS250)
        assertArrayEquals(bytes(0x13, 0x03, 0x28, 0x03), s.configureCommands[0])
    }

    @Test fun configCommand_odr50_125dps_matches_python() {
        // test_set_all_config: ODR=50Hz, range=125dps → [0x13, 0x03, 0x27, 0x04]
        val s = GyroscopeBmi160(GyroscopeBmi160.Odr.HZ50, GyroscopeBmi160.Range.DPS125)
        assertArrayEquals(bytes(0x13, 0x03, 0x27, 0x04), s.configureCommands[0])
    }

    @Test fun bmi270_offsets_command() {
        // Writes to OFFSET register 0x06 with [x, y, z]
        val cmd = GyroscopeBmi270.Offsets(x = 0x11, y = 0x22, z = 0x33)
        assertArrayEquals(bytes(0x13, 0x06, 0x11, 0x22, 0x33), cmd.commandData)
    }

    @Test fun bmi160_packedDataRegister() = assertEquals(0x07, sensor.packedDataRegister)

    @Test fun bmi270_packedDataRegister_differs() {
        val bmi270 = GyroscopeBmi270(GyroscopeBmi270.Odr.HZ100, GyroscopeBmi270.Range.DPS2000)
        assertEquals(0x05, bmi270.packedDataRegister)
    }
}

/** Gyroscope BMI160 data-handler tests. */
class GyroscopeBmi160DataHandlerTest {

    // Reference vector: test_data_handler_bmi160.py at 500dps → (262.409, 499.497, -499.512)
    // Raw packet: [0x13, 0x05, 0x3e, 0x43, 0xff, 0x7f, 0x00, 0x80]
    @Test fun parseSample_500dps_matchesPythonReference() {
        val s = GyroscopeBmi160(GyroscopeBmi160.Odr.HZ100, GyroscopeBmi160.Range.DPS500)
        val sample = s.parseSample(bytes(0x13, 0x05, 0x3E, 0x43, 0xFF, 0x7F, 0x00, 0x80))
        // scale = 65.6  →  x = 0x433e(17214)/65.6, y = 0x7fff(32767)/65.6, z = 0x8000(-32768)/65.6
        assertEquals(262.409f, sample.x, 0.01f)
        assertEquals(499.497f, sample.y, 0.01f)
        assertEquals(-499.512f, sample.z, 0.01f)
    }

    @Test fun parsePackedSamples_1000dps_threeSamples() {
        // Packed layout: [module, register, (x,y,z) × 3] — 20 bytes total.
        val s = GyroscopeBmi160(GyroscopeBmi160.Odr.HZ100, GyroscopeBmi160.Range.DPS1000)
        val samples = s.parsePackedSamples(
            bytes(
                0x13, 0x07,
                // sample 0: (0x0100, 0x0200, 0x0300)
                0x00, 0x01, 0x00, 0x02, 0x00, 0x03,
                // sample 1: (0x0400, 0x0500, 0x0600)
                0x00, 0x04, 0x00, 0x05, 0x00, 0x06,
                // sample 2: (-1, -1, -1)
                0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
            ),
        )
        assertEquals(3, samples.size)
        // scale = 32.8
        assertEquals(0x0100.toFloat() / 32.8f, samples[0].x, 0.01f)
        assertEquals(0x0500.toFloat() / 32.8f, samples[1].y, 0.01f)
        assertEquals(-1f / 32.8f, samples[2].z, 0.01f)
    }

    @Test fun subscribeRegister_unpacked_is0x05() =
        assertEquals(0x05, GyroscopeBmi160().dataRegister)

    @Test fun subscribeRegister_packed_is0x07() =
        assertEquals(0x07, GyroscopeBmi160().packedDataRegister)

    // Reference vector: test_gyro_bmi160.py::TestGyroBmi160HighFreqDataHandler::test_rotation_data_handler
    // 1000dps range, packet = b'\x13\x07\x09\x15\xad\x26\x08\xde\x8a\x1a\x0d\x26\x65\xe4\x8d\x20\xac\x27\x73\xec'
    @Test fun parsePackedSamples_1000dps_pythonReference() {
        val s = GyroscopeBmi160(GyroscopeBmi160.Odr.HZ100, GyroscopeBmi160.Range.DPS1000)
        val samples = s.parsePackedSamples(
            bytes(
                0x13, 0x07,
                0x09, 0x15, 0xAD, 0x26, 0x08, 0xDE,  // sample 0
                0x8A, 0x1A, 0x0D, 0x26, 0x65, 0xE4,  // sample 1
                0x8D, 0x20, 0xAC, 0x27, 0x73, 0xEC,  // sample 2
            ),
        )
        assertEquals(3, samples.size)
        assertEquals(164.177f, samples[0].x, 0.01f)
        assertEquals(301.860f, samples[0].y, 0.01f)
        assertEquals(-265.122f, samples[0].z, 0.01f)
        assertEquals(207.134f, samples[1].x, 0.01f)
        assertEquals(296.982f, samples[1].y, 0.01f)
        assertEquals(-215.457f, samples[1].z, 0.01f)
        assertEquals(254.055f, samples[2].x, 0.01f)
        assertEquals(309.634f, samples[2].y, 0.01f)
        assertEquals(-152.591f, samples[2].z, 0.01f)
    }
}

/** Type-erased gyroscope API tests. */
class GyroscopeTypeErasedTest {

    @Test fun make_bmi160_impl0_producesBmi160() {
        val g = Gyroscope.make(impl = 0, odrHz = 100.0, rangeDps = 2000f)
        assertNotNull(g)
        assertTrue(g is Gyroscope.Bmi160, "expected Bmi160 for impl=0")
    }

    @Test fun make_bmi270_impl1_producesBmi270() {
        val g = Gyroscope.make(impl = 1, odrHz = 100.0, rangeDps = 2000f)
        assertNotNull(g)
        assertTrue(g is Gyroscope.Bmi270, "expected Bmi270 for impl=1")
    }

    @Test fun make_unknownImpl_returnsNull() = assertNull(Gyroscope.make(impl = 99))

    @Test fun odrSnapping_snapsToNearest() {
        // 123 Hz → nearest supported is 100 Hz
        val g = Gyroscope.make(impl = 0, odrHz = 123.0, rangeDps = 2000f)!!
        assertEquals(100.0, g.odrHz, 0.0)
    }

    @Test fun rangeSnapping_snapsToNearest() {
        // 600 dps → nearest supported is 500 dps
        val g = Gyroscope.make(impl = 0, odrHz = 100.0, rangeDps = 600f)!!
        assertEquals(500f, g.rangeDps, 0f)
    }

    @Test fun withOdr_returnsUpdated() {
        val g = Gyroscope.make(impl = 0, odrHz = 100.0, rangeDps = 2000f)!!
        assertEquals(400.0, g.withOdr(400.0).odrHz, 0.0)
    }

    @Test fun withRange_returnsUpdated() {
        val g = Gyroscope.make(impl = 1, odrHz = 100.0, rangeDps = 2000f)!!
        assertEquals(250f, g.withRange(250f).rangeDps, 0f)
    }

    @Test fun loggerKey_isAngularVelocity() =
        assertEquals("angular-velocity", Gyroscope.make(impl = 0)!!.loggerKey)

    @Test fun forwarding_startCommand_usesGyroModule() =
        assertArrayEquals(bytes(0x13, 0x01, 0x01), Gyroscope.make(impl = 0)!!.startCommand)
}
