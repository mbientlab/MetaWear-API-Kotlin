package com.mbientlab.metawear.sensor

import com.mbientlab.metawear.bytes
import com.mbientlab.metawear.model.MetaWearException
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.protocol.Packet
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Ported from MWModuleCommandTests.swift — "Accelerometer Bosch — Orientation
 * Detection" suite. Reference: test_accelerometer_bosch.py (MbientLab C++
 * SDK), class TestOrientation.
 */
class AccelerometerBoschOrientationTest {

    @Test fun enableCommand_bmi160() {
        // Happy path: orientation detection is BMI160-only.
        val cmd = AccelerometerBosch.EnableOrientation(AccelerometerBosch.ChipVariant.BMI160)
        assertArrayEquals(bytes(0x03, 0x0F, 0x01, 0x00), cmd.commandData)
    }

    @Test fun enableCommand_throwsOnBmi270() {
        // Chip guard: constructing on BMI270 must throw with the legacy
        // verbatim error string.
        val e = assertThrows(MetaWearException.OperationFailed::class.java) {
            AccelerometerBosch.EnableOrientation(AccelerometerBosch.ChipVariant.BMI270)
        }
        assertEquals(
            "Operation failed: Orientation requires a BMI160 module, which this device lacks.",
            e.message,
        )
    }

    @Test fun disableCommand() =
        // Disable is unguarded — writing zero bits to a register the BMI270
        // doesn't react to is harmless.
        assertArrayEquals(bytes(0x03, 0x0F, 0x00, 0x01), AccelerometerBosch.DisableOrientation().commandData)

    // All 8 orientations from test_handle_response. Parse index = (responseByte >> 1) & 0x07

    private fun parse(b: Int) = AccelerometerBosch.parseOrientation(bytes(0x03, 0x11, b))

    @Test fun parse_faceUpLandscapeRight() =
        assertEquals(AccelerometerBosch.SensorOrientation.FACE_UP_LANDSCAPE_RIGHT, parse(0x07))

    @Test fun parse_faceUpPortraitUpright() =
        assertEquals(AccelerometerBosch.SensorOrientation.FACE_UP_PORTRAIT_UPRIGHT, parse(0x01))

    @Test fun parse_faceUpPortraitUpsideDown() =
        assertEquals(AccelerometerBosch.SensorOrientation.FACE_UP_PORTRAIT_UPSIDE_DOWN, parse(0x03))

    @Test fun parse_faceUpLandscapeLeft() =
        assertEquals(AccelerometerBosch.SensorOrientation.FACE_UP_LANDSCAPE_LEFT, parse(0x05))

    @Test fun parse_faceDownLandscapeRight() =
        assertEquals(AccelerometerBosch.SensorOrientation.FACE_DOWN_LANDSCAPE_RIGHT, parse(0x0F))

    @Test fun parse_faceDownLandscapeLeft() =
        assertEquals(AccelerometerBosch.SensorOrientation.FACE_DOWN_LANDSCAPE_LEFT, parse(0x0D))

    @Test fun parse_faceDownPortraitUpright() =
        assertEquals(AccelerometerBosch.SensorOrientation.FACE_DOWN_PORTRAIT_UPRIGHT, parse(0x09))

    @Test fun parse_faceDownPortraitUpsideDown() =
        assertEquals(AccelerometerBosch.SensorOrientation.FACE_DOWN_PORTRAIT_UPSIDE_DOWN, parse(0x0B))
}

/**
 * Ported from MWModuleCommandTests.swift — "Accelerometer Bosch — Any-Motion
 * Detection" suite. Reference: test_accelerometer_bosch.py, class TestAnyMotion.
 */
class AccelerometerBoschAnyMotionTest {

    // Config: range=8g, count=4, threshold=0.75g, noMotionDefault=0x14
    // threshold_byte = round(0.75 * 512 / 8) - 1 = 48 - 1 = 47 = 0x2f

    @Test fun configCommand_bmi160() {
        // BMI160 gets 4 payload bytes (extra noMotion byte)
        val cmd = AccelerometerBosch.ConfigureAnyMotion(
            chip = AccelerometerBosch.ChipVariant.BMI160, count = 4, thresholdG = 0.75f, rangeG = 8.0f,
        )
        assertArrayEquals(bytes(0x03, 0x0A, 0x03, 0x2F, 0x14, 0x14), cmd.commandData)
    }

    @Test fun configCommand_bmi270() {
        // BMI270 gets 3 payload bytes
        val cmd = AccelerometerBosch.ConfigureAnyMotion(
            chip = AccelerometerBosch.ChipVariant.BMI270, count = 4, thresholdG = 0.75f, rangeG = 8.0f,
        )
        assertArrayEquals(bytes(0x03, 0x0A, 0x03, 0x2F, 0x14), cmd.commandData)
    }

    @Test fun enableCommand() =
        assertArrayEquals(bytes(0x03, 0x09, 0x07, 0x00), AccelerometerBosch.EnableAnyMotion().commandData)

    @Test fun disableCommand() =
        assertArrayEquals(bytes(0x03, 0x09, 0x00, 0x7F), AccelerometerBosch.DisableAnyMotion().commandData)

    // Response parsing: byte layout — bit6=sign(0=positive), bit5=z, bit4=y, bit3=x

    private fun parse(b: Int) = AccelerometerBosch.parseAnyMotion(bytes(0x03, 0x0B, b))

    @Test fun parse_positiveZ() = assertEquals(
        AccelerometerBosch.AnyMotionEvent(isPositive = true, xAxisActive = false, yAxisActive = false, zAxisActive = true),
        parse(0x22),
    )

    @Test fun parse_negativeZ() = assertEquals(
        AccelerometerBosch.AnyMotionEvent(isPositive = false, xAxisActive = false, yAxisActive = false, zAxisActive = true),
        parse(0x62),
    )

    @Test fun parse_negativeY() = assertEquals(
        AccelerometerBosch.AnyMotionEvent(isPositive = false, xAxisActive = false, yAxisActive = true, zAxisActive = false),
        parse(0x52),
    )

    @Test fun parse_positiveY() = assertEquals(
        AccelerometerBosch.AnyMotionEvent(isPositive = true, xAxisActive = false, yAxisActive = true, zAxisActive = false),
        parse(0x12),
    )

    @Test fun parse_positiveX() = assertEquals(
        AccelerometerBosch.AnyMotionEvent(isPositive = true, xAxisActive = true, yAxisActive = false, zAxisActive = false),
        parse(0x0A),
    )

    @Test fun parse_negativeX() = assertEquals(
        AccelerometerBosch.AnyMotionEvent(isPositive = false, xAxisActive = true, yAxisActive = false, zAxisActive = false),
        parse(0x4A),
    )
}

/**
 * Ported from MWModuleCommandTests.swift — "Accelerometer Bosch — Tap
 * Detection" suite. Reference: test_accelerometer_bosch.py, class TestTapDetector.
 */
class AccelerometerBoschTapTest {

    // Timing byte = (shock << 7) | (quiet << 6) | window
    // tap_th = round(threshold * 32 / range)

    @Test fun configureSingleTap() {
        // range=16g, threshold=2.0g, shock=50ms(0), quiet=30ms(0), window=250ms(4, default)
        // timingByte = (0<<7)|(0<<6)|4 = 0x04; tapTh = round(2.0*32/16) = 4 = 0x04
        val cmd = AccelerometerBosch.ConfigureTap(
            shockTime = AccelerometerBosch.TapShockTime.MS50, thresholdG = 2.0f, rangeG = 16.0f,
        )
        assertArrayEquals(bytes(0x03, 0x0D, 0x04, 0x04), cmd.commandData)
    }

    @Test fun configureDoubleTap() {
        // range=8g, threshold=1.0g, window=50ms(0), quiet=20ms(1), shock=75ms(1)
        // timingByte = (1<<7)|(1<<6)|0 = 0xc0; tapTh = round(1.0*32/8) = 4 = 0x04
        val cmd = AccelerometerBosch.ConfigureTap(
            shockTime = AccelerometerBosch.TapShockTime.MS75,
            quietTime = AccelerometerBosch.TapQuietTime.MS20,
            doubleTapWindow = AccelerometerBosch.DoubleTapWindow.MS50,
            thresholdG = 1.0f,
            rangeG = 8.0f,
        )
        assertArrayEquals(bytes(0x03, 0x0D, 0xC0, 0x04), cmd.commandData)
    }

    @Test fun enableSingleTap() =
        // bit1 = single
        assertArrayEquals(
            bytes(0x03, 0x0C, 0x02, 0x00),
            AccelerometerBosch.EnableTap(single = true, double = false).commandData,
        )

    @Test fun enableDoubleTap() =
        // bit0 = double
        assertArrayEquals(
            bytes(0x03, 0x0C, 0x01, 0x00),
            AccelerometerBosch.EnableTap(single = false, double = true).commandData,
        )

    @Test fun disableTap() =
        assertArrayEquals(bytes(0x03, 0x0C, 0x00, 0x03), AccelerometerBosch.DisableTap().commandData)

    // Response parsing: type = byte & 0x03  (1=double, 2=single),  isPositive = ((byte>>5)&1)==0

    private fun parse(b: Int) = AccelerometerBosch.parseTap(bytes(0x03, 0x0E, b))

    @Test fun parseSingleTap_positive() = assertEquals(
        AccelerometerBosch.TapEvent(type = AccelerometerBosch.TapType.SINGLE, isPositive = true),
        parse(0x12),
    )

    @Test fun parseSingleTap_negative() = assertEquals(
        AccelerometerBosch.TapEvent(type = AccelerometerBosch.TapType.SINGLE, isPositive = false),
        parse(0x32),
    )

    @Test fun parseDoubleTap_positive() = assertEquals(
        AccelerometerBosch.TapEvent(type = AccelerometerBosch.TapType.DOUBLE, isPositive = true),
        parse(0x11),
    )

    @Test fun parseDoubleTap_negative() = assertEquals(
        AccelerometerBosch.TapEvent(type = AccelerometerBosch.TapType.DOUBLE, isPositive = false),
        parse(0x31),
    )
}

/**
 * Ported from MWAccelerometerBMI160Tests.swift — "BMI160 — Step Counter Setup",
 * "BMI160 — Step Counter Data", and "BMI160 — Step Detector" suites.
 * Reference: test_accelerometer_bmi160.py (MbientLab C++ SDK).
 */
class AccelerometerBmi160StepsTest {

    // test_set_mode NORMAL: set_mode + enable + write_config → [0x03, 0x18, 0x15, 0x0B]
    @Test fun set_mode_normal() = assertArrayEquals(
        bytes(0x03, 0x18, 0x15, 0x0B),
        AccelerometerBmi160Steps.ConfigureStepCounter(AccelerometerBmi160Steps.StepCounterMode.NORMAL).commandData,
    )

    // test_set_mode SENSITIVE → [0x03, 0x18, 0x2D, 0x08]
    @Test fun set_mode_sensitive() = assertArrayEquals(
        bytes(0x03, 0x18, 0x2D, 0x08),
        AccelerometerBmi160Steps.ConfigureStepCounter(AccelerometerBmi160Steps.StepCounterMode.SENSITIVE).commandData,
    )

    // test_set_mode ROBUST → [0x03, 0x18, 0x1D, 0x0F]
    @Test fun set_mode_robust() = assertArrayEquals(
        bytes(0x03, 0x18, 0x1D, 0x0F),
        AccelerometerBmi160Steps.ConfigureStepCounter(AccelerometerBmi160Steps.StepCounterMode.ROBUST).commandData,
    )

    // test_get_step_count_value: response [0x03, 0x9A, 0x2B, 0x00] → 43 steps
    @Test fun parse_step_count_43() =
        assertEquals(43, AccelerometerBmi160Steps.parseStepCount(bytes(0x03, 0x9A, 0x2B, 0x00)))

    // test_read_step_counter: subscribe then read → [0x03, 0x9A]
    @Test fun read_step_counter_command() =
        assertArrayEquals(bytes(0x03, 0x9A), AccelerometerBmi160Steps.ReadStepCounter().commandData)

    // test_read_step_counter_silent: read without subscriber → [0x03, 0xDA]
    @Test fun read_step_counter_silent_command() =
        assertArrayEquals(bytes(0x03, 0xDA), AccelerometerBmi160Steps.ReadStepCounterSilent().commandData)

    // test_subscribe_detector: subscribe sends [0x03, 0x19, 0x01]
    @Test fun subscribe_detector_register() = assertArrayEquals(
        bytes(0x03, 0x19, 0x01),
        Packet.command(Module.ACCELEROMETER, AccelerometerBmi160Steps.STEP_DETECTOR_REGISTER, 0x01),
    )

    // test_enable_detector → [0x03, 0x17, 0x01, 0x00]
    @Test fun enable_step_detector() =
        assertArrayEquals(bytes(0x03, 0x17, 0x01, 0x00), AccelerometerBmi160Steps.EnableStepDetector().commandData)

    // test_disable_detector → [0x03, 0x17, 0x00, 0x01]
    @Test fun disable_step_detector() =
        assertArrayEquals(bytes(0x03, 0x17, 0x00, 0x01), AccelerometerBmi160Steps.DisableStepDetector().commandData)

    // test_get_detection: response [0x03, 0x19, 0x01] → 1
    @Test fun parse_step_detection_1() =
        assertEquals(1, AccelerometerBmi160Steps.parseStepDetection(bytes(0x03, 0x19, 0x01)))

    // test_get_detection (no step): response [0x03, 0x19, 0x00] → 0
    @Test fun parse_step_detection_0() =
        assertEquals(0, AccelerometerBmi160Steps.parseStepDetection(bytes(0x03, 0x19, 0x00)))
}

/**
 * Ported from MWModuleCommandTests.swift — "MWAccelerometer Generic API"
 * suite. Reference: test_accelerometer.py (MbientLab C++ SDK) —
 * mbl_mw_acc_set_odr / mbl_mw_acc_set_range snap to the nearest supported value.
 */
class AccelerometerTypeErasedTest {

    private fun bmi160(acc: Accelerometer?): AccelerometerBmi160 {
        assertTrue(acc is Accelerometer.Bmi160, "Expected Bmi160")
        return (acc as Accelerometer.Bmi160).sensor
    }

    private fun bmi270(acc: Accelerometer?): AccelerometerBmi270 {
        assertTrue(acc is Accelerometer.Bmi270, "Expected Bmi270")
        return (acc as Accelerometer.Bmi270).sensor
    }

    // ---- BMI160 ----

    @Test fun bmi160_odr_snapsToNearest_55Hz() {
        // 55 Hz is between 50 and 100; nearest is 50 Hz → confByte 0x27
        val s = bmi160(Accelerometer.make(impl = 1, odrHz = 55.0, rangeG = 2f))
        assertEquals(0x27, s.configureCommands[0][2].toInt() and 0xFF)
    }

    @Test fun bmi160_odr_snappedValue_is50Hz() {
        val s = bmi160(Accelerometer.make(impl = 1, odrHz = 55.0, rangeG = 2f))
        assertEquals(50.0, s.odr.hz, 0.0)
    }

    @Test fun bmi160_range_snapsToNearest_14_75G() {
        // 14.75 g → nearest supported is 16 g → rangeByte 0x0C
        val s = bmi160(Accelerometer.make(impl = 1, odrHz = 100.0, rangeG = 14.75f))
        assertEquals(0x0C, s.configureCommands[0][3].toInt() and 0xFF)
    }

    @Test fun bmi160_range_snappedValue_is16G() {
        val s = bmi160(Accelerometer.make(impl = 1, odrHz = 100.0, rangeG = 14.75f))
        assertEquals(16.0f, s.range.rangeG, 0f)
    }

    @Test fun bmi160_fullCommand_odr55_range14_75() {
        // Combined: 50 Hz (0x27) + 16 g (0x0C)
        val s = bmi160(Accelerometer.make(impl = 1, odrHz = 55.0, rangeG = 14.75f))
        assertArrayEquals(bytes(0x03, 0x03, 0x27, 0x0C), s.configureCommands[0])
    }

    @Test fun bmi160_unknownImpl_returnsNull() = assertNull(Accelerometer.make(impl = 99))

    // ---- BMI270 ----

    @Test fun bmi270_odr_snapsToNearest_55Hz() {
        // 55 Hz → nearest is 50 Hz → confByte 0xA7
        val s = bmi270(Accelerometer.make(impl = 4, odrHz = 55.0, rangeG = 2f))
        assertEquals(0xA7, s.configureCommands[0][2].toInt() and 0xFF)
    }

    @Test fun bmi270_range_snapsToNearest_14_75G() {
        // 14.75 g → nearest is 16 g → rangeByte 0x03
        val s = bmi270(Accelerometer.make(impl = 4, odrHz = 100.0, rangeG = 14.75f))
        assertEquals(0x03, s.configureCommands[0][3].toInt() and 0xFF)
    }

    @Test fun bmi270_fullCommand_odr100_range2G() {
        // 100 Hz (0xA8) + 2 g (0x00)
        val s = bmi270(Accelerometer.make(impl = 4, odrHz = 100.0, rangeG = 2f))
        assertArrayEquals(bytes(0x03, 0x03, 0xA8, 0x00), s.configureCommands[0])
    }
}
