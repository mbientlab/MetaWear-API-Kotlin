package com.mbientlab.metawear.sensor

import com.mbientlab.metawear.bytes
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Magnetometer command byte-layout tests. */
class MagnetometerCommandTest {

    private val sensor = Magnetometer(Magnetometer.Preset.LOW_POWER)

    @Test fun startCommand() = assertArrayEquals(bytes(0x15, 0x01, 0x01), sensor.startCommand)
    @Test fun stopCommand() = assertArrayEquals(bytes(0x15, 0x01, 0x00), sensor.stopCommand)

    @Test fun lowPowerPreset_configBytes() {
        // LOW_POWER: xy=3, z=3, odr=0 → xyByte = (3-1)/2 = 1, zByte = 3-1 = 2
        val cmds = sensor.configureCommands
        assertEquals(2, cmds.size)
        assertArrayEquals(bytes(0x15, 0x04, 0x01, 0x02), cmds[0])
        assertArrayEquals(bytes(0x15, 0x03, 0x00), cmds[1])
    }

    @Test fun highAccuracyPreset_configBytes() {
        // HIGH_ACCURACY: xy=47, z=83, odr=5(20Hz)
        // xyByte = (47-1)/2 = 23 = 0x17, zByte = 83-1 = 82 = 0x52
        val cmds = Magnetometer(Magnetometer.Preset.HIGH_ACCURACY).configureCommands
        assertArrayEquals(bytes(0x15, 0x04, 0x17, 0x52), cmds[0])
        assertArrayEquals(bytes(0x15, 0x03, 0x05), cmds[1])
    }

    @Test fun enableBFieldSampling() =
        // test_enable_b_field_sampling: [0x15, 0x02, 0x01, 0x00]
        assertArrayEquals(bytes(0x15, 0x02, 0x01, 0x00), sensor.enableCommand)

    @Test fun disableBFieldSampling() =
        // test_disable_b_field_sampling: [0x15, 0x02, 0x00, 0x01]
        assertArrayEquals(bytes(0x15, 0x02, 0x00, 0x01), sensor.disableCommand)

    // --- Reference vectors from test_magnetometer_bmm150.py test_preset ---

    @Test fun regularPreset_configBytes_matchesPython() {
        // REGULAR: xy=9, z=15, odr=10Hz → [0x15, 0x04, 0x04, 0x0e], [0x15, 0x03, 0x00]
        val cmds = Magnetometer(Magnetometer.Preset.REGULAR).configureCommands
        assertArrayEquals(bytes(0x15, 0x04, 0x04, 0x0E), cmds[0])
        assertArrayEquals(bytes(0x15, 0x03, 0x00), cmds[1])
    }

    @Test fun enhancedRegularPreset_configBytes_matchesPython() {
        // ENHANCED_REGULAR: xy=15, z=27, odr=10Hz → [0x15, 0x04, 0x07, 0x1a], [0x15, 0x03, 0x00]
        val cmds = Magnetometer(Magnetometer.Preset.ENHANCED_REGULAR).configureCommands
        assertArrayEquals(bytes(0x15, 0x04, 0x07, 0x1A), cmds[0])
        assertArrayEquals(bytes(0x15, 0x03, 0x00), cmds[1])
    }

    // --- Manual configuration ---

    @Test fun manualConfigure_customRepsAndOdr() {
        // Manual init with custom reps & ODR bypasses preset mapping.
        val s = Magnetometer(xyReps = 9, zReps = 15, odr = Magnetometer.Odr.HZ30)
        assertNull(s.preset)
        val cmds = s.configureCommands
        assertArrayEquals(bytes(0x15, 0x04, 0x04, 0x0E), cmds[0])
        assertArrayEquals(bytes(0x15, 0x03, 0x07), cmds[1])  // 30Hz = 7
    }

    @Test fun manualConfigureCommand_bytes() {
        // Magnetometer.Configure is a standalone command; mirrors mbl_mw_mag_bmm150_configure.
        val cmd = Magnetometer.Configure(xyReps = 3, zReps = 3, odr = Magnetometer.Odr.HZ10)
        assertArrayEquals(bytes(0x15, 0x04, 0x01, 0x02, 0x15, 0x03, 0x00), cmd.commandData)
    }

    // --- Suspend ---

    @Test fun suspendCommand_rev2_matchesPython() =
        // test_suspend on revision >= 2: [0x15, 0x01, 0x02]
        assertArrayEquals(bytes(0x15, 0x01, 0x02), Magnetometer.Suspend().commandData)

    // --- ODR enum raw values match C++ MblMwMagBmm150Odr ---

    @Test fun odrRawValues_matchCpp() {
        assertEquals(0, Magnetometer.Odr.HZ10.raw)
        assertEquals(1, Magnetometer.Odr.HZ2.raw)
        assertEquals(2, Magnetometer.Odr.HZ6.raw)
        assertEquals(3, Magnetometer.Odr.HZ8.raw)
        assertEquals(4, Magnetometer.Odr.HZ15.raw)
        assertEquals(5, Magnetometer.Odr.HZ20.raw)
        assertEquals(6, Magnetometer.Odr.HZ25.raw)
        assertEquals(7, Magnetometer.Odr.HZ30.raw)
    }

    // --- Data register / packed register ---

    @Test fun dataRegisters() {
        assertEquals(0x05, sensor.dataRegister)
        assertEquals(0x09, sensor.packedDataRegister)
    }
}

/** Magnetometer data-handler tests. */
class MagnetometerDataHandlerTest {

    // Reference from test_b_field_data: parse b'\x15\x05\x4e\xf0\x53\x0a\x75\x04'
    // → (-251.125, 165.1875, 71.3125) µT at 16 LSB/µT
    @Test fun parseSample_matchesPythonReference() {
        val sample = Magnetometer().parseSample(bytes(0x15, 0x05, 0x4E, 0xF0, 0x53, 0x0A, 0x75, 0x04))
        assertEquals(-251.1250f, sample.x, 0.001f)
        assertEquals(165.1875f, sample.y, 0.001f)
        assertEquals(71.3125f, sample.z, 0.001f)
    }

    // Reference from test_packed_bfield_data: 20-byte packet → 3 samples
    @Test fun parsePackedSamples_matchesPythonReference() {
        val samples = Magnetometer().parsePackedSamples(
            bytes(
                0x15, 0x09,
                0xB6, 0x0C, 0x72, 0xF7, 0x89, 0xEE,   // sample 0
                0xB6, 0x0B, 0x5A, 0xF8, 0x32, 0xEE,   // sample 1
                0xE6, 0x0A, 0xA2, 0xF7, 0x25, 0xEF,   // sample 2
            ),
        )
        assertEquals(3, samples.size)
        // sample 0: (203.375, -136.875, -279.4375)
        assertEquals(203.375f, samples[0].x, 0.01f)
        assertEquals(-136.875f, samples[0].y, 0.01f)
        assertEquals(-279.437f, samples[0].z, 0.01f)
        // sample 1: (187.375, -122.375, -284.875)
        assertEquals(187.375f, samples[1].x, 0.01f)
        assertEquals(-122.375f, samples[1].y, 0.01f)
        assertEquals(-284.874f, samples[1].z, 0.01f)
        // sample 2: (174.375, -133.875, -269.6875)
        assertEquals(174.375f, samples[2].x, 0.01f)
        assertEquals(-133.875f, samples[2].y, 0.01f)
        assertEquals(-269.687f, samples[2].z, 0.01f)
    }
}
