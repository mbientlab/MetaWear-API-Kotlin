package com.mbientlab.metawear.sensor

import com.mbientlab.metawear.bytes
import com.mbientlab.metawear.model.MetaWearException
import com.mbientlab.metawear.protocol.Streamable
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

// Ported from MWModuleCommandTests.swift — the sensor-fusion suites
// ("Sensor Fusion Commands", "Sensor Fusion Data Handler",
// "Sensor Fusion Lifecycle (BMI160)", "Sensor Fusion Configure (BMI160/BMI270)",
// "Sensor Fusion Chip Detection").

/** Assert two command lists match element-wise. */
private fun assertCommands(expected: List<ByteArray>, actual: List<ByteArray>) {
    assertEquals(expected.size, actual.size, "command count")
    expected.zip(actual).forEachIndexed { i, (e, a) -> assertArrayEquals(e, a, "command[$i]") }
}

/** Ported from "Sensor Fusion Commands". */
class SensorFusionCommandTest {

    @Test fun quaternion_enableBit() =
        // bit 3 = 0x08
        assertArrayEquals(bytes(0x19, 0x03, 0x08, 0x00), SensorFusionQuaternion().enableCommand)

    @Test fun euler_enableBit() =
        // bit 4 = 0x10
        assertArrayEquals(bytes(0x19, 0x03, 0x10, 0x00), SensorFusionEuler().enableCommand)

    @Test fun gravity_enableBit() =
        // bit 5 = 0x20
        assertArrayEquals(bytes(0x19, 0x03, 0x20, 0x00), SensorFusionGravity().enableCommand)

    @Test fun linearAcc_enableBit() =
        // bit 6 = 0x40
        assertArrayEquals(bytes(0x19, 0x03, 0x40, 0x00), SensorFusionLinearAcceleration().enableCommand)

    @Test fun ndof_configCommand() {
        val s = SensorFusionQuaternion(mode = SensorFusionMode.NDOF)
        // mode = 1 (NDOF), rangeByte = 0 | (1 << 4) = 0x10
        assertArrayEquals(bytes(0x19, 0x02, 0x01, 0x10), s.configureCommands[0])
    }

    @Test fun start_stop() {
        val s = SensorFusionQuaternion()
        assertArrayEquals(bytes(0x19, 0x01, 0x01), s.startCommand)
        assertArrayEquals(bytes(0x19, 0x01, 0x00), s.stopCommand)
    }

    // --- Corrected data signals (bits 0/1/2, registers 0x04/0x05/0x06) ---

    @Test fun correctedAcc_registersAndBit() {
        val s = SensorFusionCorrectedAcc()
        assertEquals(0x04, s.dataRegister)
        // bit 0 = 0x01
        assertArrayEquals(bytes(0x19, 0x03, 0x01, 0x00), s.enableCommand)
        assertArrayEquals(bytes(0x19, 0x03, 0x00, 0x01), s.disableCommand)
    }

    @Test fun correctedGyro_registersAndBit() {
        val s = SensorFusionCorrectedGyro()
        assertEquals(0x05, s.dataRegister)
        // bit 1 = 0x02
        assertArrayEquals(bytes(0x19, 0x03, 0x02, 0x00), s.enableCommand)
        assertArrayEquals(bytes(0x19, 0x03, 0x00, 0x02), s.disableCommand)
    }

    @Test fun correctedMag_registersAndBit() {
        val s = SensorFusionCorrectedMag()
        assertEquals(0x06, s.dataRegister)
        // bit 2 = 0x04
        assertArrayEquals(bytes(0x19, 0x03, 0x04, 0x00), s.enableCommand)
        assertArrayEquals(bytes(0x19, 0x03, 0x00, 0x04), s.disableCommand)
    }

    // --- Mode/range matrix: reference config bytes from test_sensor_fusion_config.py ---
    //   config_byte = (gyroRange + 1) << 4 | accRange
    //   gr=0 (2000dps): [0x10, 0x11, 0x12, 0x13]
    //   gr=1 (1000dps): [0x20, 0x21, 0x22, 0x23]
    //   gr=2 (500dps):  [0x30, 0x31, 0x32, 0x33]
    //   gr=3 (250dps):  [0x40, 0x41, 0x42, 0x43]

    @Test fun ndof_acc2g_gyro2000_config() {
        // NDOF (mode=1), acc=2g (0), gyro=2000dps (0) → [0x19, 0x02, 0x01, 0x10]
        val s = SensorFusionQuaternion(
            mode = SensorFusionMode.NDOF,
            accRange = SensorFusionAccRange.G2,
            gyroRange = SensorFusionGyroRange.DPS2000,
        )
        assertArrayEquals(bytes(0x19, 0x02, 0x01, 0x10), s.configureCommands[0])
    }

    @Test fun imuPlus_acc16g_gyro250_config() {
        // IMU_PLUS (mode=2), acc=16g (3), gyro=250dps (3) → config_byte = (3+1)<<4 | 3 = 0x43
        val s = SensorFusionEuler(
            mode = SensorFusionMode.IMU_PLUS,
            accRange = SensorFusionAccRange.G16,
            gyroRange = SensorFusionGyroRange.DPS250,
        )
        assertArrayEquals(bytes(0x19, 0x02, 0x02, 0x43), s.configureCommands[0])
    }

    @Test fun compass_acc8g_gyro500_config() {
        // COMPASS (mode=3), acc=8g (2), gyro=500dps (2) → (2+1)<<4 | 2 = 0x32
        val s = SensorFusionGravity(
            mode = SensorFusionMode.COMPASS,
            accRange = SensorFusionAccRange.G8,
            gyroRange = SensorFusionGyroRange.DPS500,
        )
        assertArrayEquals(bytes(0x19, 0x02, 0x03, 0x32), s.configureCommands[0])
    }

    @Test fun m4g_acc4g_gyro1000_config() {
        // M4G (mode=4), acc=4g (1), gyro=1000dps (1) → (1+1)<<4 | 1 = 0x21
        val s = SensorFusionLinearAcceleration(
            mode = SensorFusionMode.M4G,
            accRange = SensorFusionAccRange.G4,
            gyroRange = SensorFusionGyroRange.DPS1000,
        )
        assertArrayEquals(bytes(0x19, 0x02, 0x04, 0x21), s.configureCommands[0])
    }

    @Test fun configMatrix_exhaustive_matchesPython() {
        // Exhaust the 4×4 config_masks matrix from test_sensor_fusion_config.py.
        val accCases = SensorFusionAccRange.entries
        val gyroCases = SensorFusionGyroRange.entries
        val expectedMatrix = listOf(
            listOf(0x10, 0x11, 0x12, 0x13),   // gyro index 0
            listOf(0x20, 0x21, 0x22, 0x23),   // gyro index 1
            listOf(0x30, 0x31, 0x32, 0x33),   // gyro index 2
            listOf(0x40, 0x41, 0x42, 0x43),   // gyro index 3
        )
        for ((gi, gr) in gyroCases.withIndex()) {
            for ((ai, ar) in accCases.withIndex()) {
                val s = SensorFusionQuaternion(mode = SensorFusionMode.NDOF, accRange = ar, gyroRange = gr)
                assertArrayEquals(
                    bytes(0x19, 0x02, 0x01, expectedMatrix[gi][ai]),
                    s.configureCommands[0],
                    "config byte mismatch at gyro=$gr, acc=$ar",
                )
            }
        }
    }

    // --- Standalone fire-and-forget commands ---

    @Test fun clearEnabledMask_command() =
        // Mirrors mbl_mw_sensor_fusion_clear_enabled_mask → [0x19, 0x03, 0x00, 0x7F]
        assertArrayEquals(bytes(0x19, 0x03, 0x00, 0x7F), SensorFusionClearEnabledMask().commandData)

    @Test fun resetOrientation_command_matchesRev3Python() =
        // test_sensor_fusion.py::TestSensorFusionRev3 → [0x19, 0x0f, 0x01]
        assertArrayEquals(bytes(0x19, 0x0F, 0x01), SensorFusionResetOrientation().commandData)

    @Test fun writeAccCalibration_command_matchesRev2Python() {
        // test_sensor_fusion.py::TestSensorFusionRev2::test_write_calibration_data (NDOF)
        val blob = bytes(0xf6, 0xff, 0x00, 0x00, 0x0a, 0x00, 0xe8, 0x03, 0x03, 0x00)
        val cmd = SensorFusionWriteAccCalibration(blob)
        assertArrayEquals(bytes(0x19, 0x0C) + blob, cmd.commandData)
    }

    @Test fun writeGyroCalibration_command_matchesRev2Python() {
        val blob = bytes(0x04, 0x00, 0x08, 0x00, 0x01, 0x00, 0x00, 0x00, 0x03, 0x00)
        val cmd = SensorFusionWriteGyroCalibration(blob)
        assertArrayEquals(bytes(0x19, 0x0D) + blob, cmd.commandData)
    }

    @Test fun writeMagCalibration_command_matchesRev2Python() {
        val blob = bytes(0x66, 0x00, 0x17, 0xfd, 0x8a, 0xfc, 0x7f, 0x03, 0x01, 0x00)
        val cmd = SensorFusionWriteMagCalibration(blob)
        assertArrayEquals(bytes(0x19, 0x0E) + blob, cmd.commandData)
    }

    @Test fun writeCalibration_rejectsWrongLength_withSwiftParityMessage() {
        // Swift throws MWError.operationFailed("acc calibration data must be 10 bytes; got N").
        val e = assertThrows(MetaWearException.OperationFailed::class.java) {
            SensorFusionWriteAccCalibration(bytes(0x01, 0x02, 0x03))
        }
        assertEquals("Operation failed: acc calibration data must be 10 bytes; got 3", e.message)
    }

    // --- Calibration state read signal ---

    @Test fun calibrationState_readCommand_matchesRev1Python() =
        // test_sensor_fusion.py::TestSensorFusionRev1::test_read_calibration → [0x19, 0x8b]
        assertArrayEquals(bytes(0x19, 0x8B), SensorFusionCalibrationState().readCommand)

    @Test fun calibrationState_parseResponse_matchesRev1Python() {
        // Response: [0x19, 0x8b, 0x00, 0x01, 0x02]
        //   acc=UNRELIABLE(0), gyro=LOW(1), mag=MEDIUM(2)
        val state = SensorFusionCalibrationState().parseSample(bytes(0x19, 0x8B, 0x00, 0x01, 0x02))
        assertEquals(0, state.accelerometer)
        assertEquals(1, state.gyroscope)
        assertEquals(2, state.magnetometer)
    }

    // --- Typed-range raw values match C++ ---

    @Test fun accRange_rawValues_matchCpp() {
        assertEquals(0, SensorFusionAccRange.G2.raw)
        assertEquals(1, SensorFusionAccRange.G4.raw)
        assertEquals(2, SensorFusionAccRange.G8.raw)
        assertEquals(3, SensorFusionAccRange.G16.raw)
    }

    @Test fun gyroRange_rawValues_matchCpp() {
        assertEquals(0, SensorFusionGyroRange.DPS2000.raw)
        assertEquals(1, SensorFusionGyroRange.DPS1000.raw)
        assertEquals(2, SensorFusionGyroRange.DPS500.raw)
        assertEquals(3, SensorFusionGyroRange.DPS250.raw)
    }
}

/**
 * Ported from "Sensor Fusion Data Handler".
 * All reference vectors from test_sensor_fusion.py::test_received_data.
 */
class SensorFusionDataHandlerTest {

    @Test fun correctedAcc_parse() {
        // Expected: (-3.3799, 15.9995, -15.9995, accuracy=0)
        // Raw float values in bytes: x=-3379.86, y=15999.5, z=-15999.5 → scaled ÷1000
        val out = SensorFusionCorrectedAcc().parseSample(
            bytes(
                0x19, 0x04,
                0x20, 0x3e, 0x53, 0xc5,   // x
                0x0c, 0xfe, 0x79, 0x46,   // y
                0x0c, 0xfe, 0x79, 0xc6,   // z
                0x00,                     // accuracy
            ),
        )
        assertEquals(-3.3799f, out.x, 0.01f)
        assertEquals(15.9995f, out.y, 0.01f)
        assertEquals(-15.9995f, out.z, 0.01f)
        assertEquals(0, out.accuracy)
    }

    @Test fun correctedGyro_parse() {
        // Expected: (72.669, -56.096, 528.820, accuracy=0) — no scaling
        val out = SensorFusionCorrectedGyro().parseSample(
            bytes(
                0x19, 0x05,
                0x7a, 0x56, 0x91, 0x42,   // x = 72.669
                0xb4, 0x62, 0x60, 0xc2,   // y = -56.096
                0x73, 0x34, 0x04, 0x44,   // z = 528.820
                0x00,
            ),
        )
        assertEquals(72.669f, out.x, 0.01f)
        assertEquals(-56.096f, out.y, 0.01f)
        assertEquals(528.820f, out.z, 0.01f)
        assertEquals(0, out.accuracy)
    }

    @Test fun correctedMag_parse() {
        // Expected: (32.500, -14.800, 29.700, accuracy=3)
        val out = SensorFusionCorrectedMag().parseSample(
            bytes(
                0x19, 0x06,
                0x00, 0x00, 0x02, 0x42,   // x = 32.5
                0xcd, 0xcc, 0x6c, 0xc1,   // y = -14.8
                0x9a, 0x99, 0xed, 0x41,   // z = 29.7
                0x03,                     // accuracy = HIGH
            ),
        )
        assertEquals(32.500f, out.x, 0.01f)
        assertEquals(-14.800f, out.y, 0.01f)
        assertEquals(29.700f, out.z, 0.01f)
        assertEquals(3, out.accuracy)
    }

    @Test fun quaternion_parse() {
        // Expected: w=0.940, x=-0.050, y=-0.154, z=-0.301
        val q = SensorFusionQuaternion().parseSample(
            bytes(
                0x19, 0x07,
                0x1b, 0x9b, 0x70, 0x3f,   // w
                0x8c, 0x5e, 0x4d, 0xbd,   // x
                0x07, 0x7f, 0x1d, 0xbe,   // y
                0x78, 0x02, 0x9a, 0xbe,   // z
            ),
        )
        assertEquals(0.940f, q.w, 0.01f)
        assertEquals(-0.050f, q.x, 0.01f)
        assertEquals(-0.154f, q.y, 0.01f)
        assertEquals(-0.301f, q.z, 0.01f)
    }

    @Test fun euler_parse() {
        // Expected: heading=24.747, pitch=-120.862, roll=-33.046, yaw=24.747
        // Python stores order heading, pitch, yaw, roll in the struct.
        val e = SensorFusionEuler().parseSample(
            bytes(
                0x19, 0x08,
                0xb1, 0xf9, 0xc5, 0x41,   // heading = 24.747
                0x44, 0xb9, 0xf1, 0xc2,   // pitch = -120.862
                0x1a, 0x2f, 0x04, 0xc2,   // roll = -33.046
                0xb1, 0xf9, 0xc5, 0x41,   // yaw = 24.747
            ),
        )
        assertEquals(24.747f, e.heading, 0.01f)
        assertEquals(-120.862f, e.pitch, 0.01f)
        assertEquals(24.747f, e.yaw, 0.01f)
    }

    @Test fun gravity_parse() {
        // Expected: (0.042, 0.826, -0.562)
        val g = SensorFusionGravity().parseSample(
            bytes(
                0x19, 0x09,
                0xee, 0x20, 0xd3, 0x3e,
                0xb2, 0x93, 0x01, 0x41,
                0x04, 0x59, 0xb0, 0xc0,
            ),
        )
        assertEquals(0.042f, g.x, 0.01f)
        assertEquals(0.826f, g.y, 0.01f)
        assertEquals(-0.562f, g.z, 0.01f)
    }

    @Test fun linearAcc_parse() {
        // Expected: (0.296, 1.439, -0.380)
        val l = SensorFusionLinearAcceleration().parseSample(
            bytes(
                0x19, 0x0a,
                0x2f, 0xca, 0x39, 0x40,
                0x86, 0xd4, 0x61, 0x41,
                0x80, 0x4c, 0x6e, 0xc0,
            ),
        )
        assertEquals(0.296f, l.x, 0.01f)
        assertEquals(1.439f, l.y, 0.01f)
        assertEquals(-0.380f, l.z, 0.01f)
    }
}

/**
 * Ported from "Sensor Fusion Lifecycle (BMI160)".
 * Reference: MetaWear-SDK-Cpp/test/test_sensor_fusion.py::test_sensor_control.
 * The C++ test asserts that start + stop produces a fixed byte sequence per mode,
 * with the OUTPUT_ENABLE byte (`[0x19, 0x03, mask, 0x00]`) carrying the bit for
 * whichever signal was enabled. Our SDK splits this across four properties:
 *   configureCommands → fusion.config + underlying acc/gyro/mag configs
 *   enableCommands    → underlying enable_sampling commands
 *   startCommands     → underlying start + fusion.enable_mask + fusion.start
 *   stopCommands      → fusion.stop + fusion.clear_mask + underlying stops
 *   disableCommands   → underlying disable_sampling commands
 */
class SensorFusionLifecycleBmi160Test {

    // NDOF — acc + gyro + mag (8 start commands, 8 stop commands)

    @Test fun ndof_quaternion_startSequence() {
        val s = SensorFusionQuaternion(mode = SensorFusionMode.NDOF)
        assertCommands(
            listOf(
                bytes(0x03, 0x02, 0x01, 0x00),
                bytes(0x13, 0x02, 0x01, 0x00),
                bytes(0x15, 0x02, 0x01, 0x00),
            ),
            s.enableCommands,
        )
        assertCommands(
            listOf(
                bytes(0x03, 0x01, 0x01),
                bytes(0x13, 0x01, 0x01),
                bytes(0x15, 0x01, 0x01),
                bytes(0x19, 0x03, 0x08, 0x00),  // QUATERNION = bit 3
                bytes(0x19, 0x01, 0x01),
            ),
            s.startCommands,
        )
    }

    @Test fun ndof_quaternion_stopSequence() {
        val s = SensorFusionQuaternion(mode = SensorFusionMode.NDOF)
        assertCommands(
            listOf(
                bytes(0x19, 0x01, 0x00),
                bytes(0x19, 0x03, 0x00, 0x7F),
                bytes(0x03, 0x01, 0x00),
                bytes(0x13, 0x01, 0x00),
                bytes(0x15, 0x01, 0x00),
            ),
            s.stopCommands,
        )
        assertCommands(
            listOf(
                bytes(0x03, 0x02, 0x00, 0x01),
                bytes(0x13, 0x02, 0x00, 0x01),
                bytes(0x15, 0x02, 0x00, 0x01),
            ),
            s.disableCommands,
        )
    }

    /**
     * Verifies that each of the 7 signals plugs the correct bit into the
     * OUTPUT_ENABLE byte at index 3 of the NDOF startCommands.
     */
    @Test fun ndof_outputEnableMask_perSignal() {
        // (signal, expected mask bit)
        val cases: List<Pair<Streamable<*>, Int>> = listOf(
            SensorFusionCorrectedAcc(mode = SensorFusionMode.NDOF) to 0x01,          // bit 0
            SensorFusionCorrectedGyro(mode = SensorFusionMode.NDOF) to 0x02,         // bit 1
            SensorFusionCorrectedMag(mode = SensorFusionMode.NDOF) to 0x04,          // bit 2
            SensorFusionQuaternion(mode = SensorFusionMode.NDOF) to 0x08,            // bit 3
            SensorFusionEuler(mode = SensorFusionMode.NDOF) to 0x10,                 // bit 4
            SensorFusionGravity(mode = SensorFusionMode.NDOF) to 0x20,               // bit 5
            SensorFusionLinearAcceleration(mode = SensorFusionMode.NDOF) to 0x40,    // bit 6
        )
        for ((signal, expectedBit) in cases) {
            // Index 3 of the 5-element startCommands: [acc, gyro, mag, enable_mask, fusion_start]
            assertArrayEquals(
                bytes(0x19, 0x03, expectedBit, 0x00),
                signal.startCommands[3],
                "OUTPUT_ENABLE mask wrong for signal with bit 0x${expectedBit.toString(16)}",
            )
        }
    }

    // IMU_PLUS — acc + gyro (no mag)

    @Test fun imuPlus_quaternion_startSequence() {
        val s = SensorFusionQuaternion(mode = SensorFusionMode.IMU_PLUS)
        assertCommands(
            listOf(
                bytes(0x03, 0x02, 0x01, 0x00),
                bytes(0x13, 0x02, 0x01, 0x00),
            ),
            s.enableCommands,
        )
        assertCommands(
            listOf(
                bytes(0x03, 0x01, 0x01),
                bytes(0x13, 0x01, 0x01),
                bytes(0x19, 0x03, 0x08, 0x00),
                bytes(0x19, 0x01, 0x01),
            ),
            s.startCommands,
        )
    }

    @Test fun imuPlus_quaternion_stopSequence() {
        val s = SensorFusionQuaternion(mode = SensorFusionMode.IMU_PLUS)
        assertCommands(
            listOf(
                bytes(0x19, 0x01, 0x00),
                bytes(0x19, 0x03, 0x00, 0x7F),
                bytes(0x03, 0x01, 0x00),
                bytes(0x13, 0x01, 0x00),
            ),
            s.stopCommands,
        )
        assertCommands(
            listOf(
                bytes(0x03, 0x02, 0x00, 0x01),
                bytes(0x13, 0x02, 0x00, 0x01),
            ),
            s.disableCommands,
        )
    }

    // COMPASS — acc + mag (no gyro)

    @Test fun compass_quaternion_startSequence() {
        val s = SensorFusionQuaternion(mode = SensorFusionMode.COMPASS)
        assertCommands(
            listOf(
                bytes(0x03, 0x02, 0x01, 0x00),
                bytes(0x15, 0x02, 0x01, 0x00),
            ),
            s.enableCommands,
        )
        assertCommands(
            listOf(
                bytes(0x03, 0x01, 0x01),
                bytes(0x15, 0x01, 0x01),
                bytes(0x19, 0x03, 0x08, 0x00),
                bytes(0x19, 0x01, 0x01),
            ),
            s.startCommands,
        )
    }

    @Test fun compass_quaternion_stopSequence() {
        val s = SensorFusionQuaternion(mode = SensorFusionMode.COMPASS)
        assertCommands(
            listOf(
                bytes(0x19, 0x01, 0x00),
                bytes(0x19, 0x03, 0x00, 0x7F),
                bytes(0x03, 0x01, 0x00),
                bytes(0x15, 0x01, 0x00),
            ),
            s.stopCommands,
        )
        assertCommands(
            listOf(
                bytes(0x03, 0x02, 0x00, 0x01),
                bytes(0x15, 0x02, 0x00, 0x01),
            ),
            s.disableCommands,
        )
    }

    // M4G — acc + mag (no gyro)

    @Test fun m4g_quaternion_startSequence() {
        val s = SensorFusionQuaternion(mode = SensorFusionMode.M4G)
        assertCommands(
            listOf(
                bytes(0x03, 0x02, 0x01, 0x00),
                bytes(0x15, 0x02, 0x01, 0x00),
            ),
            s.enableCommands,
        )
        assertCommands(
            listOf(
                bytes(0x03, 0x01, 0x01),
                bytes(0x15, 0x01, 0x01),
                bytes(0x19, 0x03, 0x08, 0x00),
                bytes(0x19, 0x01, 0x01),
            ),
            s.startCommands,
        )
    }

    @Test fun m4g_quaternion_stopSequence() {
        val s = SensorFusionQuaternion(mode = SensorFusionMode.M4G)
        assertCommands(
            listOf(
                bytes(0x19, 0x01, 0x00),
                bytes(0x19, 0x03, 0x00, 0x7F),
                bytes(0x03, 0x01, 0x00),
                bytes(0x15, 0x01, 0x00),
            ),
            s.stopCommands,
        )
        assertCommands(
            listOf(
                bytes(0x03, 0x02, 0x00, 0x01),
                bytes(0x15, 0x02, 0x00, 0x01),
            ),
            s.disableCommands,
        )
    }

    // Single-command forms preserve fusion-only meaning (regression check)

    @Test fun singleCommandForms_areFusionOnly() {
        // The single-command forms keep their fusion-only meaning so the unit
        // tests in SensorFusionCommandTest continue to pass.
        val s = SensorFusionQuaternion(mode = SensorFusionMode.NDOF)
        assertArrayEquals(bytes(0x19, 0x03, 0x08, 0x00), s.enableCommand)
        assertArrayEquals(bytes(0x19, 0x01, 0x01), s.startCommand)
        assertArrayEquals(bytes(0x19, 0x01, 0x00), s.stopCommand)
        assertArrayEquals(bytes(0x19, 0x03, 0x00, 0x08), s.disableCommand)
    }
}

/**
 * Ported from "Sensor Fusion Configure (BMI160)".
 * Reference: MetaWear-SDK-Cpp/test/test_sensor_fusion_config.py::test_configure_*.
 * `mbl_mw_sensor_fusion_write_config` issues fusion-config + underlying configs
 * per (mode, chip). Our SDK exposes this via `configureCommands`, which the
 * device flushes before `enableCommands`/`startCommands`.
 */
class SensorFusionConfigureBmi160Test {

    // NDOF — fusion + acc(100Hz) + gyro(100Hz) + mag

    @Test fun ndof_2g_2000dps_configure() {
        // From test_configure_ndof — gr=0, ar=0:
        //   fusion config_byte = 0x10, acc range_byte = 0x03, gyro range_byte = 0x00
        val s = SensorFusionQuaternion(
            mode = SensorFusionMode.NDOF,
            accRange = SensorFusionAccRange.G2,
            gyroRange = SensorFusionGyroRange.DPS2000,
            chip = SensorFusionChip.BMI160,
        )
        assertCommands(
            listOf(
                bytes(0x19, 0x02, 0x01, 0x10),     // fusion: NDOF, gr=0, ar=0
                bytes(0x03, 0x03, 0x28, 0x03),     // acc:  100Hz, ±2g
                bytes(0x13, 0x03, 0x28, 0x00),     // gyro: 100Hz, 2000dps
                bytes(0x15, 0x04, 0x04, 0x0E),     // mag:  xy_reps=9, z_reps=15
                bytes(0x15, 0x03, 0x06),           // mag:  ODR=25Hz
            ),
            s.configureCommands,
        )
    }

    @Test fun ndof_16g_250dps_configure() {
        // gr=3, ar=3 → fusion config_byte = (3+1)<<4 | 3 = 0x43,
        //              acc range_byte = 0x0C, gyro range_byte = 0x03
        val s = SensorFusionEuler(
            mode = SensorFusionMode.NDOF,
            accRange = SensorFusionAccRange.G16,
            gyroRange = SensorFusionGyroRange.DPS250,
            chip = SensorFusionChip.BMI160,
        )
        assertCommands(
            listOf(
                bytes(0x19, 0x02, 0x01, 0x43),
                bytes(0x03, 0x03, 0x28, 0x0C),
                bytes(0x13, 0x03, 0x28, 0x03),
                bytes(0x15, 0x04, 0x04, 0x0E),
                bytes(0x15, 0x03, 0x06),
            ),
            s.configureCommands,
        )
    }

    @Test fun ndof_configMatrix_exhaustive_matchesPython() {
        // Mirrors test_configure_ndof's full 4×4 matrix.
        val accCases = listOf(
            SensorFusionAccRange.G2 to 0x03,
            SensorFusionAccRange.G4 to 0x05,
            SensorFusionAccRange.G8 to 0x08,
            SensorFusionAccRange.G16 to 0x0C,
        )
        val gyroCases = listOf(
            SensorFusionGyroRange.DPS2000 to 0x00,
            SensorFusionGyroRange.DPS1000 to 0x01,
            SensorFusionGyroRange.DPS500 to 0x02,
            SensorFusionGyroRange.DPS250 to 0x03,
        )
        val configMasks = listOf(
            listOf(0x10, 0x11, 0x12, 0x13),
            listOf(0x20, 0x21, 0x22, 0x23),
            listOf(0x30, 0x31, 0x32, 0x33),
            listOf(0x40, 0x41, 0x42, 0x43),
        )
        for ((gi, gyroCase) in gyroCases.withIndex()) {
            val (gr, gByte) = gyroCase
            for ((ai, accCase) in accCases.withIndex()) {
                val (ar, aByte) = accCase
                val s = SensorFusionQuaternion(
                    mode = SensorFusionMode.NDOF,
                    accRange = ar,
                    gyroRange = gr,
                    chip = SensorFusionChip.BMI160,
                )
                assertCommands(
                    listOf(
                        bytes(0x19, 0x02, 0x01, configMasks[gi][ai]),
                        bytes(0x03, 0x03, 0x28, aByte),
                        bytes(0x13, 0x03, 0x28, gByte),
                        bytes(0x15, 0x04, 0x04, 0x0E),
                        bytes(0x15, 0x03, 0x06),
                    ),
                    s.configureCommands,
                )
            }
        }
    }

    // IMU_PLUS — fusion + acc(100Hz) + gyro(100Hz) — no mag

    @Test fun imuPlus_2g_2000dps_configure() {
        val s = SensorFusionQuaternion(
            mode = SensorFusionMode.IMU_PLUS,
            accRange = SensorFusionAccRange.G2,
            gyroRange = SensorFusionGyroRange.DPS2000,
            chip = SensorFusionChip.BMI160,
        )
        assertCommands(
            listOf(
                bytes(0x19, 0x02, 0x02, 0x10),     // fusion: IMU_PLUS
                bytes(0x03, 0x03, 0x28, 0x03),     // acc:  100Hz, ±2g
                bytes(0x13, 0x03, 0x28, 0x00),     // gyro: 100Hz, 2000dps
            ),
            s.configureCommands,
        )
    }

    // COMPASS — fusion + acc(25Hz) + mag — no gyro

    @Test fun compass_2g_configure() {
        val s = SensorFusionQuaternion(
            mode = SensorFusionMode.COMPASS,
            accRange = SensorFusionAccRange.G2,
            gyroRange = SensorFusionGyroRange.DPS2000,
            chip = SensorFusionChip.BMI160,
        )
        assertCommands(
            listOf(
                bytes(0x19, 0x02, 0x03, 0x10),     // fusion: COMPASS
                bytes(0x03, 0x03, 0x26, 0x03),     // acc: 25Hz, ±2g (confByte = 0x26)
                bytes(0x15, 0x04, 0x04, 0x0E),
                bytes(0x15, 0x03, 0x06),
            ),
            s.configureCommands,
        )
    }

    // M4G — fusion + acc(50Hz) + mag — no gyro

    @Test fun m4g_2g_configure() {
        val s = SensorFusionQuaternion(
            mode = SensorFusionMode.M4G,
            accRange = SensorFusionAccRange.G2,
            gyroRange = SensorFusionGyroRange.DPS2000,
            chip = SensorFusionChip.BMI160,
        )
        assertCommands(
            listOf(
                bytes(0x19, 0x02, 0x04, 0x10),     // fusion: M4G
                bytes(0x03, 0x03, 0x27, 0x03),     // acc: 50Hz, ±2g (confByte = 0x27)
                bytes(0x15, 0x04, 0x04, 0x0E),
                bytes(0x15, 0x03, 0x06),
            ),
            s.configureCommands,
        )
    }

    // SLEEP — fusion config only (parity with C++ — no underlying writes)

    @Test fun sleep_writesOnlyFusionConfig() {
        val s = SensorFusionQuaternion(
            mode = SensorFusionMode.SLEEP,
            accRange = SensorFusionAccRange.G2,
            gyroRange = SensorFusionGyroRange.DPS2000,
            chip = SensorFusionChip.BMI160,
        )
        assertCommands(
            listOf(bytes(0x19, 0x02, 0x00, 0x10)),
            s.configureCommands,
        )
    }
}

/**
 * Ported from "Sensor Fusion Configure (BMI270)".
 * On BMI270 boards the gyro module reports `implementation = 1`, the acc reports
 * `implementation = 4`. Our SDK takes a `chip` parameter on each fusion signal.
 * The acc config byte differs from BMI160: bit[7]=filter_perf=1 for ODR>=12.5 Hz
 * (so acc_conf becomes 0xA8 / 0xA6 / 0xA7), and the range byte is 0-based
 * (0/1/2/3 instead of 0x03/0x05/0x08/0x0C). Gyro and mag bytes are unchanged.
 */
class SensorFusionConfigureBmi270Test {

    @Test fun ndof_2g_2000dps_configure_bmi270() {
        val s = SensorFusionQuaternion(
            mode = SensorFusionMode.NDOF,
            accRange = SensorFusionAccRange.G2,
            gyroRange = SensorFusionGyroRange.DPS2000,
            chip = SensorFusionChip.BMI270,
        )
        assertCommands(
            listOf(
                bytes(0x19, 0x02, 0x01, 0x10),     // fusion: NDOF (unchanged)
                bytes(0x03, 0x03, 0xA8, 0x00),     // acc: 100Hz, ±2g (BMI270: confByte=0xA8, range=0)
                bytes(0x13, 0x03, 0x28, 0x00),     // gyro: 100Hz, 2000dps (unchanged)
                bytes(0x15, 0x04, 0x04, 0x0E),
                bytes(0x15, 0x03, 0x06),
            ),
            s.configureCommands,
        )
    }

    @Test fun ndof_16g_250dps_configure_bmi270() {
        val s = SensorFusionEuler(
            mode = SensorFusionMode.NDOF,
            accRange = SensorFusionAccRange.G16,
            gyroRange = SensorFusionGyroRange.DPS250,
            chip = SensorFusionChip.BMI270,
        )
        assertCommands(
            listOf(
                bytes(0x19, 0x02, 0x01, 0x43),
                bytes(0x03, 0x03, 0xA8, 0x03),     // BMI270: range 16g = 0x03
                bytes(0x13, 0x03, 0x28, 0x03),
                bytes(0x15, 0x04, 0x04, 0x0E),
                bytes(0x15, 0x03, 0x06),
            ),
            s.configureCommands,
        )
    }

    @Test fun compass_2g_configure_bmi270() {
        val s = SensorFusionQuaternion(
            mode = SensorFusionMode.COMPASS,
            accRange = SensorFusionAccRange.G2,
            gyroRange = SensorFusionGyroRange.DPS2000,
            chip = SensorFusionChip.BMI270,
        )
        assertCommands(
            listOf(
                bytes(0x19, 0x02, 0x03, 0x10),
                bytes(0x03, 0x03, 0xA6, 0x00),     // BMI270 25Hz confByte = 0xA6
                bytes(0x15, 0x04, 0x04, 0x0E),
                bytes(0x15, 0x03, 0x06),
            ),
            s.configureCommands,
        )
    }

    @Test fun m4g_2g_configure_bmi270() {
        val s = SensorFusionQuaternion(
            mode = SensorFusionMode.M4G,
            accRange = SensorFusionAccRange.G2,
            gyroRange = SensorFusionGyroRange.DPS2000,
            chip = SensorFusionChip.BMI270,
        )
        assertCommands(
            listOf(
                bytes(0x19, 0x02, 0x04, 0x10),
                bytes(0x03, 0x03, 0xA7, 0x00),     // BMI270 50Hz confByte = 0xA7
                bytes(0x15, 0x04, 0x04, 0x0E),
                bytes(0x15, 0x03, 0x06),
            ),
            s.configureCommands,
        )
    }
}

/** Ported from "Sensor Fusion Chip Detection". */
class SensorFusionChipTest {

    @Test fun chip_fromGyroImpl_matchesCpp() {
        assertEquals(SensorFusionChip.BMI160, SensorFusionChip.fromGyroImpl(0))
        assertEquals(SensorFusionChip.BMI270, SensorFusionChip.fromGyroImpl(1))
        assertNull(SensorFusionChip.fromGyroImpl(7))
    }

    @Test fun chip_fromAccImpl_matchesCpp() {
        assertEquals(SensorFusionChip.BMI160, SensorFusionChip.fromAccImpl(1))
        assertEquals(SensorFusionChip.BMI270, SensorFusionChip.fromAccImpl(4))
        assertNull(SensorFusionChip.fromAccImpl(0))
    }
}
