package com.mbientlab.metawear.sensor

import com.mbientlab.metawear.bytes
import com.mbientlab.metawear.model.MetaWearException
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.protocol.Packet
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Ported from MWModuleCommandTests.swift — the step counter / step detector
 * portion of the "Accelerometer BMI270 — Python Vectors" suite. Reference:
 * test_accelerometer_bmi270.py (MbientLab C++ SDK).
 */
class AccelerometerBmi270StepsTest {

    // TestBmi270StepCounterSetup.test_set_mode
    //
    // Python sequence: set_step_counter_trigger(1) + enable_step_counter +
    // write_step_counter_config. The final wire byte-vector asserted is
    //   [3, 8, 7, 0, 14, 1, 0]  ⇒  [0x03, 0x08, 0x07, 0x00, 0x0E, 0x01, 0x00]

    @Test fun stepCounter_writeConfig_trigger1() = assertArrayEquals(
        bytes(0x03, 0x08, 0x07, 0x00, 0x0E, 0x01, 0x00),
        AccelerometerBmi270Steps.ConfigureStepCounter(trigger = 1).configCommand,
    )

    @Test fun stepCounter_interruptEnable() =
        // Python flow enables step-counter interrupt via FEATURE_INTERRUPT_ENABLE (0x07).
        assertArrayEquals(
            bytes(0x03, 0x07, 0x02, 0x00),
            AccelerometerBmi270Steps.ConfigureStepCounter(trigger = 1).interruptEnableCommand,
        )

    @Test fun stepCounter_featureEnable() = assertArrayEquals(
        bytes(0x03, 0x06, 0x02, 0x00),
        AccelerometerBmi270Steps.ConfigureStepCounter(trigger = 1).featureEnableCommand,
    )

    // TestBmi270StepCounterData.test_get_step_count_value
    // Python notifies b'\x03\x0b\x14\x00' → expected = 20.
    @Test fun stepCounter_parse_20() =
        assertEquals(20, AccelerometerBmi270Steps.parseStepCount(bytes(0x03, 0x0B, 0x14, 0x00)))

    // TestBmi270StepCounterRead.test_read_step_counter
    @Test fun stepCounter_readCommand() =
        assertArrayEquals(bytes(0x03, 0x8B), AccelerometerBmi270Steps.ReadStepCounter().commandData)

    // TestBmi270StepDetectorData — subscribe/unsubscribe/enable/disable/get

    @Test fun stepDetector_register_is0x0B() =
        assertEquals(0x0B, AccelerometerBmi270Steps.STEP_REGISTER)

    @Test fun stepDetector_subscribeCommand() =
        // subscribe sends [0x03, 0x0B, 0x01]
        assertArrayEquals(
            bytes(0x03, 0x0B, 0x01),
            Packet.command(Module.ACCELEROMETER, AccelerometerBmi270Steps.STEP_REGISTER, 0x01),
        )

    @Test fun stepDetector_enable() =
        // Python test_enable_detector: [0x03, 0x06, 0x80, 0x00]
        assertArrayEquals(
            bytes(0x03, 0x06, 0x80, 0x00),
            AccelerometerBmi270Steps.EnableStepDetector().featureEnableCommand,
        )

    @Test fun stepDetector_enable_interruptEnable() = assertArrayEquals(
        bytes(0x03, 0x07, 0x80, 0x00),
        AccelerometerBmi270Steps.EnableStepDetector().interruptEnableCommand,
    )

    @Test fun stepDetector_disable() =
        // Python test_disable_detector: [0x03, 0x06, 0x00, 0x80]
        assertArrayEquals(
            bytes(0x03, 0x06, 0x00, 0x80),
            AccelerometerBmi270Steps.DisableStepDetector().featureDisableCommand,
        )

    @Test fun stepDetector_disable_interruptDisable() = assertArrayEquals(
        bytes(0x03, 0x07, 0x00, 0x80),
        AccelerometerBmi270Steps.DisableStepDetector().interruptDisableCommand,
    )

    @Test fun stepDetector_parseDetection_zero() =
        // Python test_get_detection: b'\x03\x0b\x00' → 0
        assertEquals(0, AccelerometerBmi270Steps.parseStepDetection(bytes(0x03, 0x0B, 0x00)))

    @Test fun stepDetector_parseDetection_one() =
        assertEquals(1, AccelerometerBmi270Steps.parseStepDetection(bytes(0x03, 0x0B, 0x01)))
}

/** Ported from MWModuleCommandTests.swift — "BMI270 Feature Commands" suite. */
class AccelerometerBmi270FeaturesTest {

    // ---- Activity ----

    @Test fun activity_enable() {
        val cmd = AccelerometerBmi270Features.EnableActivityDetection()
        assertArrayEquals(bytes(0x03, 0x07, 0x04, 0x00), cmd.interruptEnableCommand)
        assertArrayEquals(bytes(0x03, 0x06, 0x04, 0x00), cmd.featureEnableCommand)
        assertEquals(2, cmd.commands.size)
    }

    @Test fun activity_disable() {
        val cmd = AccelerometerBmi270Features.DisableActivityDetection()
        assertArrayEquals(bytes(0x03, 0x07, 0x00, 0x04), cmd.interruptDisableCommand)
        assertArrayEquals(bytes(0x03, 0x06, 0x00, 0x04), cmd.featureDisableCommand)
    }

    @Test fun activity_register() = assertEquals(0x0C, AccelerometerBmi270Features.ACTIVITY_REGISTER)

    @Test fun activity_parse_still() =
        // payload byte 0x00 → (0x00 >> 1) = 0 = still
        assertEquals(
            AccelerometerBmi270Features.Activity.STILL,
            AccelerometerBmi270Features.parseActivity(bytes(0x03, 0x0C, 0x00)),
        )

    @Test fun activity_parse_walking() =
        // payload byte 0x02 → (0x02 >> 1) = 1 = walking
        assertEquals(
            AccelerometerBmi270Features.Activity.WALKING,
            AccelerometerBmi270Features.parseActivity(bytes(0x03, 0x0C, 0x02)),
        )

    @Test fun activity_parse_running() =
        // payload byte 0x04 → (0x04 >> 1) = 2 = running
        assertEquals(
            AccelerometerBmi270Features.Activity.RUNNING,
            AccelerometerBmi270Features.parseActivity(bytes(0x03, 0x0C, 0x04)),
        )

    @Test fun activity_parse_unknown() =
        // payload byte 0x06 → (0x06 >> 1) = 3 = unknown
        assertEquals(
            AccelerometerBmi270Features.Activity.UNKNOWN,
            AccelerometerBmi270Features.parseActivity(bytes(0x03, 0x0C, 0x06)),
        )

    @Test fun activity_parse_tooShort_throws() {
        assertThrows(MetaWearException.OperationFailed::class.java) {
            AccelerometerBmi270Features.parseActivity(bytes(0x03, 0x0C))
        }
    }

    // ---- Wrist events (shared parser) ----

    @Test fun wrist_register() = assertEquals(0x0A, AccelerometerBmi270Features.WRIST_EVENT_REGISTER)

    @Test fun wrist_parse_wakeup() {
        // byte b: type = b & 0x03, code = b >> 2 ; b = 0x00 → kind=wakeup, code=unknown
        val e = AccelerometerBmi270Features.parseWristEvent(bytes(0x03, 0x0A, 0x00))
        assertEquals(AccelerometerBmi270Features.WristEventKind.WAKEUP, e.kind)
        assertEquals(AccelerometerBmi270Features.WristGestureCode.UNKNOWN, e.gestureCode)
    }

    @Test fun wrist_parse_gesture_pivotUp() {
        // type=1 (gesture), code=2 (pivotUp) → byte = (2 << 2) | 1 = 0x09
        val e = AccelerometerBmi270Features.parseWristEvent(bytes(0x03, 0x0A, 0x09))
        assertEquals(AccelerometerBmi270Features.WristEventKind.GESTURE, e.kind)
        assertEquals(AccelerometerBmi270Features.WristGestureCode.PIVOT_UP, e.gestureCode)
    }

    @Test fun wrist_parse_gesture_armFlickOut() {
        // type=1, code=5 → byte = (5 << 2) | 1 = 0x15
        val e = AccelerometerBmi270Features.parseWristEvent(bytes(0x03, 0x0A, 0x15))
        assertEquals(AccelerometerBmi270Features.WristEventKind.GESTURE, e.kind)
        assertEquals(AccelerometerBmi270Features.WristGestureCode.ARM_FLICK_OUT, e.gestureCode)
    }

    @Test fun wrist_parse_tooShort_throws() {
        assertThrows(MetaWearException.OperationFailed::class.java) {
            AccelerometerBmi270Features.parseWristEvent(bytes(0x03, 0x0A))
        }
    }

    // ---- Wrist gesture configure ----

    @Test fun wristGesture_defaults_leftArm() {
        // arm=left → armByte=0x00; peak=0x0332, samples=0x0050, duration=0x0064
        val cfg = AccelerometerBmi270Features.ConfigureWristGesture()
        assertArrayEquals(
            bytes(
                0x03, 0x08, 0x08,
                0x00, 0x00,
                0x32, 0x03,  // peak LE
                0x50, 0x00,  // samples LE
                0x64, 0x00,  // duration LE
            ),
            cfg.commandData,
        )
    }

    @Test fun wristGesture_rightArm_customValues() {
        val cfg = AccelerometerBmi270Features.ConfigureWristGesture(
            arm = AccelerometerBmi270Features.WristArm.RIGHT,
            peak = 0x1234, samples = 0x00AB, duration = 0x0200,
        )
        assertArrayEquals(
            bytes(
                0x03, 0x08, 0x08,
                0x10, 0x00,
                0x34, 0x12,
                0xAB, 0x00,
                0x00, 0x02,
            ),
            cfg.commandData,
        )
    }

    @Test fun wristGesture_enable() {
        val cmd = AccelerometerBmi270Features.EnableWristGesture()
        assertArrayEquals(bytes(0x03, 0x07, 0x10, 0x00), cmd.interruptEnableCommand)
        assertArrayEquals(bytes(0x03, 0x06, 0x10, 0x00), cmd.featureEnableCommand)
    }

    @Test fun wristGesture_disable() {
        val cmd = AccelerometerBmi270Features.DisableWristGesture()
        assertArrayEquals(bytes(0x03, 0x07, 0x00, 0x10), cmd.interruptDisableCommand)
        assertArrayEquals(bytes(0x03, 0x06, 0x00, 0x10), cmd.featureDisableCommand)
    }

    // ---- Wrist wakeup configure ----

    @Test fun wristWakeup_defaults_matchCppDefaults() {
        // C++ initializer bytes: A8 05 EE 06 00 04 BC 02 B3 00 85 07
        val cfg = AccelerometerBmi270Features.ConfigureWristWakeup()
        assertArrayEquals(
            bytes(
                0x03, 0x08, 0x09,
                0xA8, 0x05,
                0xEE, 0x06,
                0x00, 0x04,
                0xBC, 0x02,
                0xB3, 0x00,
                0x85, 0x07,
            ),
            cfg.commandData,
        )
    }

    @Test fun wristWakeup_custom() {
        val cfg = AccelerometerBmi270Features.ConfigureWristWakeup(
            minAngleFocus = 0x0001,
            minAngleNonFocus = 0x0002,
            maxTiltLR = 0x0003,
            maxTiltLL = 0x0004,
            maxTiltPD = 0x0005,
            maxTiltPU = 0x0006,
        )
        assertArrayEquals(
            bytes(
                0x03, 0x08, 0x09,
                0x01, 0x00,
                0x02, 0x00,
                0x03, 0x00,
                0x04, 0x00,
                0x05, 0x00,
                0x06, 0x00,
            ),
            cfg.commandData,
        )
    }

    @Test fun wristWakeup_enable() {
        val cmd = AccelerometerBmi270Features.EnableWristWakeup()
        assertArrayEquals(bytes(0x03, 0x07, 0x08, 0x00), cmd.interruptEnableCommand)
        assertArrayEquals(bytes(0x03, 0x06, 0x08, 0x00), cmd.featureEnableCommand)
    }

    @Test fun wristWakeup_disable() {
        val cmd = AccelerometerBmi270Features.DisableWristWakeup()
        assertArrayEquals(bytes(0x03, 0x07, 0x00, 0x08), cmd.interruptDisableCommand)
        assertArrayEquals(bytes(0x03, 0x06, 0x00, 0x08), cmd.featureDisableCommand)
    }

    // ---- No-motion ----

    @Test fun noMotion_configure_defaults() {
        // duration=5 → d0=0x05, d1 (hi=0) | xyz select bits = 0xE0
        // threshold=0xAA → t0=0xAA, t1=0x00
        val cfg = AccelerometerBmi270Features.ConfigureNoMotion()
        assertArrayEquals(bytes(0x03, 0x08, 0x02, 0x05, 0xE0, 0xAA, 0x00), cfg.commandData)
    }

    @Test fun noMotion_configure_largeDuration_andThreshold() {
        // duration=0x0123 → d0=0x23, d1 hi=0x01 | 0xE0 = 0xE1
        // threshold=0x0456 → t0=0x56, t1=0x04
        val cfg = AccelerometerBmi270Features.ConfigureNoMotion(duration = 0x0123, threshold = 0x0456)
        assertArrayEquals(bytes(0x03, 0x08, 0x02, 0x23, 0xE1, 0x56, 0x04), cfg.commandData)
    }

    @Test fun noMotion_configure_noAxisSelect() {
        // all axes off → d1 hi bits zero
        val cfg = AccelerometerBmi270Features.ConfigureNoMotion(
            duration = 5, threshold = 0xAA,
            selectX = false, selectY = false, selectZ = false,
        )
        assertArrayEquals(bytes(0x03, 0x08, 0x02, 0x05, 0x00, 0xAA, 0x00), cfg.commandData)
    }

    @Test fun noMotion_enable() {
        val cmd = AccelerometerBmi270Features.EnableNoMotion()
        assertArrayEquals(bytes(0x03, 0x07, 0x20, 0x00), cmd.interruptEnableCommand)
        assertArrayEquals(bytes(0x03, 0x06, 0x20, 0x00), cmd.featureEnableCommand)
    }

    @Test fun noMotion_disable() {
        val cmd = AccelerometerBmi270Features.DisableNoMotion()
        assertArrayEquals(bytes(0x03, 0x07, 0x00, 0x20), cmd.interruptDisableCommand)
        assertArrayEquals(bytes(0x03, 0x06, 0x00, 0x20), cmd.featureDisableCommand)
    }

    // ---- Significant motion ----

    @Test fun sigMotion_configure_defaultBlocksize() {
        // blocksize default = 250 (0x00FA) → lo=0xFA hi=0x00
        // FEATURE_CONFIG index for sig_motion = 3
        val cfg = AccelerometerBmi270Features.ConfigureSignificantMotion()
        assertArrayEquals(bytes(0x03, 0x08, 0x03, 0xFA, 0x00), cfg.commandData)
    }

    @Test fun sigMotion_configure_largeBlocksize() {
        // 0x1234 → lo=0x34 hi=0x12
        val cfg = AccelerometerBmi270Features.ConfigureSignificantMotion(blocksize = 0x1234)
        assertArrayEquals(bytes(0x03, 0x08, 0x03, 0x34, 0x12), cfg.commandData)
    }

    @Test fun sigMotion_enable() {
        // FEATURE_ENABLE / FEATURE_INTERRUPT_ENABLE bit 0x01.
        val cmd = AccelerometerBmi270Features.EnableSignificantMotion()
        assertArrayEquals(bytes(0x03, 0x07, 0x01, 0x00), cmd.interruptEnableCommand)
        assertArrayEquals(bytes(0x03, 0x06, 0x01, 0x00), cmd.featureEnableCommand)
    }

    @Test fun sigMotion_disable() {
        val cmd = AccelerometerBmi270Features.DisableSignificantMotion()
        assertArrayEquals(bytes(0x03, 0x07, 0x00, 0x01), cmd.interruptDisableCommand)
        assertArrayEquals(bytes(0x03, 0x06, 0x00, 0x01), cmd.featureDisableCommand)
    }

    // ---- Downsampling ----

    @Test fun downsampling_allZeros() = assertArrayEquals(
        bytes(0x03, 0x11, 0x00),
        AccelerometerBmi270Features.SetDownsampling().commandData,
    )

    @Test fun downsampling_gyroOrdinalOnly() =
        // bits 0-2 = 0b011
        assertArrayEquals(
            bytes(0x03, 0x11, 0x03),
            AccelerometerBmi270Features.SetDownsampling(gyroOrdinal = 3).commandData,
        )

    @Test fun downsampling_accOrdinalOnly() =
        // bits 4-6 = 0b101 → 0x50
        assertArrayEquals(
            bytes(0x03, 0x11, 0x50),
            AccelerometerBmi270Features.SetDownsampling(accOrdinal = 5).commandData,
        )

    @Test fun downsampling_allFieldsSet() {
        // gyroOrdinal=7 → bits 0-2 = 0x07 ; gyroFilter → bit 3 = 0x08
        // accOrdinal=7 → bits 4-6 = 0x70 ; accFilter → bit 7 = 0x80
        val cmd = AccelerometerBmi270Features.SetDownsampling(
            gyroOrdinal = 7, gyroFilterData = true,
            accOrdinal = 7, accFilterData = true,
        )
        assertArrayEquals(bytes(0x03, 0x11, 0xFF), cmd.commandData)
    }

    @Test fun downsampling_onlyFilters() = assertArrayEquals(
        bytes(0x03, 0x11, 0x88),
        AccelerometerBmi270Features.SetDownsampling(gyroFilterData = true, accFilterData = true).commandData,
    )
}
