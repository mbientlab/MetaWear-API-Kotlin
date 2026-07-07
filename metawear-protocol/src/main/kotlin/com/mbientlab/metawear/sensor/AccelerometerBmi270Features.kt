package com.mbientlab.metawear.sensor

import com.mbientlab.metawear.model.MetaWearException
import com.mbientlab.metawear.protocol.Command
import com.mbientlab.metawear.protocol.CommandSequence
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.protocol.Packet
import com.mbientlab.metawear.protocol.PacketParser

// The BMI270-only accelerometer features:
// `AccelerometerBmi270Steps` and `AccelerometerBmi270Features`.
//
// Registers (AccelerometerBmi270Register, module 0x03):
//   0x06 = FEATURE_ENABLE               enable bit in byte 0; disable bit in byte 1
//   0x07 = FEATURE_INTERRUPT_ENABLE     same bit layout as FEATURE_ENABLE
//   0x08 = FEATURE_CONFIG               first payload byte = feature index
//   0x09 = MOTION_INTERRUPT             any-motion / no-motion / sig-motion share this register
//   0x0A = WRIST_INTERRUPT              wrist gesture + wrist wakeup notifications
//   0x0B = STEP_COUNT_INTERRUPT         step counter and step detector share this register
//   0x0C = ACTIVITY_INTERRUPT           activity classification notifications
//   0x11 = DOWNSAMPLING                 FIFO downsampling (acc + gyro)
//
// Feature bitmap (byte 0 of FEATURE_ENABLE / FEATURE_INTERRUPT_ENABLE):
//   0x01 sig_motion   0x02 step_counter  0x04 activity_out  0x08 wrist_wakeup
//   0x10 wrist_gesture 0x20 no_motion    0x40 any_motion    0x80 step_detector
//
// FEATURE_CONFIG indices:
//   0 = axis_remap   1 = any_motion   2 = no_motion   3 = sig_motion
//   4-7 = step_counter   8 = wrist_gesture   9 = wrist_wakeup

/**
 * On-chip step counter and step detector commands for the BMI270 IMU.
 *
 * Unlike the BMI160, both features on the BMI270 deliver notifications on the
 * same register (0x0B); the feature-enable bitmap distinguishes them (0x02 =
 * counter, 0x80 = detector). The step counter additionally supports a
 * watermark trigger so the chip can batch step updates rather than firing per
 * step.
 */
object AccelerometerBmi270Steps {

    // ---- Step Counter ----

    /**
     * Configures and enables the BMI270 step counter. Equivalent to:
     * set_step_counter_trigger + enable_step_counter + write_step_counter_config.
     *
     * write_step_counter_config payload:
     *   [index=0x07, param_250=0x00, param_251=0x0E, watermark_low, watermark_high|reset]
     *   With trigger=1: [0x07, 0x00, 0x0E, 0x01, 0x00] → full command [0x03, 0x08, 0x07, 0x00, 0x0E, 0x01, 0x00]
     *
     * @param trigger Watermark level: number of steps between notifications (clamped to 1…1023). Default 1.
     */
    class ConfigureStepCounter(trigger: Int = 1) : CommandSequence {
        val trigger: Int = trigger.coerceIn(1, 1023)

        /**
         * Enables step counter interrupts.
         * Sends FEATURE_INTERRUPT_ENABLE (0x07) with the step_counter bit set.
         */
        val interruptEnableCommand: ByteArray
            get() = Packet.command(Module.ACCELEROMETER, 0x07, 0x02, 0x00)

        /**
         * Enables the step counter feature.
         * Sends FEATURE_ENABLE (0x06) with the step_counter bit set.
         */
        val featureEnableCommand: ByteArray
            get() = Packet.command(Module.ACCELEROMETER, 0x06, 0x02, 0x00)

        /**
         * Writes the watermark trigger level to FEATURE_CONFIG (0x08). Sends
         * `[0x03, 0x08, 0x07, ...]` — the C++ Python test asserts this as the
         * last command in the sequence.
         *
         * step_counter_3 bitmap: [param_250, param_251, watermark_low, (watermark_high:2|reset:1|pad:5)]
         */
        val configCommand: ByteArray
            get() {
                val wm0 = trigger and 0x00FF
                val wm1 = (trigger and 0x0300) shr 8   // upper 2 bits of watermark
                return Packet.command(Module.ACCELEROMETER, 0x08, 0x07, 0x00, 0x0E, wm0, wm1)
            }

        /** All three commands in the correct order: interrupt enable, feature enable, config. */
        val allCommands: List<ByteArray>
            get() = listOf(interruptEnableCommand, featureEnableCommand, configCommand)

        /** [CommandSequence] conformance — alias for [allCommands]. */
        override val commands: List<ByteArray> get() = allCommands
    }

    /** Read step count from the board. Sends `[0x03, 0x8B]` = [module, 0x80 | 0x0B]. */
    class ReadStepCounter : Command {
        override val commandData: ByteArray
            get() = Packet.read(Module.ACCELEROMETER, 0x0B)
    }

    /** Parse a step count response `[0x03, 0x0B, low, high]` — little-endian UInt16. */
    fun parseStepCount(packet: ByteArray): Int {
        if (packet.size < 4) throw MetaWearException.OperationFailed(
            "Step counter packet too short: ${packet.size} bytes"
        )
        return PacketParser.parseUInt16LE(packet, 2)
    }

    // ---- Step Detector ----

    /**
     * Enables the BMI270 step detector. Sends two commands:
     * FEATURE_INTERRUPT_ENABLE (0x07) then FEATURE_ENABLE (0x06), both with
     * bit 7 (0x80) = step_detector set.
     */
    class EnableStepDetector : CommandSequence {
        /** `[0x03, 0x07, 0x80, 0x00]` */
        val interruptEnableCommand: ByteArray
            get() = Packet.command(Module.ACCELEROMETER, 0x07, 0x80, 0x00)

        /** `[0x03, 0x06, 0x80, 0x00]` — the command asserted by the C++ Python test. */
        val featureEnableCommand: ByteArray
            get() = Packet.command(Module.ACCELEROMETER, 0x06, 0x80, 0x00)

        override val commands: List<ByteArray>
            get() = listOf(interruptEnableCommand, featureEnableCommand)
    }

    /** Disables the BMI270 step detector. */
    class DisableStepDetector : CommandSequence {
        /** `[0x03, 0x07, 0x00, 0x80]` */
        val interruptDisableCommand: ByteArray
            get() = Packet.command(Module.ACCELEROMETER, 0x07, 0x00, 0x80)

        /** `[0x03, 0x06, 0x00, 0x80]` — the command asserted by the C++ Python test. */
        val featureDisableCommand: ByteArray
            get() = Packet.command(Module.ACCELEROMETER, 0x06, 0x00, 0x80)

        override val commands: List<ByteArray>
            get() = listOf(interruptDisableCommand, featureDisableCommand)
    }

    /** Both step counter and step detector subscribe to register 0x0B on BMI270. */
    const val STEP_REGISTER: Int = 0x0B

    /** Parse a step detection notification `[0x03, 0x0B, byte]` → 0 or 1. */
    fun parseStepDetection(packet: ByteArray): Int {
        if (packet.size < 3) throw MetaWearException.OperationFailed(
            "Step detection packet too short: ${packet.size} bytes"
        )
        return packet[2].toInt() and 0xFF
    }
}

/**
 * Additional on-chip features unique to the BMI270 IMU.
 *
 * Covers activity classification, wrist gestures and wakeup, no-motion,
 * significant-motion, and FIFO downsampling. Each feature is enabled by a
 * dedicated bit in the FEATURE_ENABLE / FEATURE_INTERRUPT_ENABLE registers,
 * then configured through the FEATURE_CONFIG register.
 */
object AccelerometerBmi270Features {

    // ---- Activity Classification ----

    /**
     * Activity class reported by the BMI270 activity-output feature.
     * Value is decoded from the notification payload byte as `byte >> 1`.
     */
    enum class Activity(val raw: Int) {
        /** Device is at rest. */
        STILL(0),

        /** Walking-cadence motion detected. */
        WALKING(1),

        /** Running-cadence motion detected. */
        RUNNING(2),

        /** Motion does not match a known class. */
        UNKNOWN(3),
    }

    /** Enables activity-output detection (bit 0x04). */
    class EnableActivityDetection : CommandSequence {
        /** `[0x03, 0x07, 0x04, 0x00]` */
        val interruptEnableCommand: ByteArray
            get() = Packet.command(Module.ACCELEROMETER, 0x07, 0x04, 0x00)

        /** `[0x03, 0x06, 0x04, 0x00]` */
        val featureEnableCommand: ByteArray
            get() = Packet.command(Module.ACCELEROMETER, 0x06, 0x04, 0x00)

        override val commands: List<ByteArray>
            get() = listOf(interruptEnableCommand, featureEnableCommand)
    }

    /** Disables activity-output detection (bit 0x04). */
    class DisableActivityDetection : CommandSequence {
        /** `[0x03, 0x07, 0x00, 0x04]` */
        val interruptDisableCommand: ByteArray
            get() = Packet.command(Module.ACCELEROMETER, 0x07, 0x00, 0x04)

        /** `[0x03, 0x06, 0x00, 0x04]` */
        val featureDisableCommand: ByteArray
            get() = Packet.command(Module.ACCELEROMETER, 0x06, 0x00, 0x04)

        override val commands: List<ByteArray>
            get() = listOf(interruptDisableCommand, featureDisableCommand)
    }

    /** Subscribe register for activity classification (`[0x03, 0x0C, byte]`). */
    const val ACTIVITY_REGISTER: Int = 0x0C

    /**
     * Parse an activity notification `[0x03, 0x0C, byte]` → [Activity].
     * C++ datainterpreter: `value = response[0] >> 1`.
     */
    fun parseActivity(packet: ByteArray): Activity {
        if (packet.size < 3) throw MetaWearException.OperationFailed(
            "Activity packet too short: ${packet.size} bytes"
        )
        val raw = (packet[2].toInt() and 0xFF) shr 1
        return Activity.entries.firstOrNull { it.raw == raw } ?: Activity.UNKNOWN
    }

    // ---- Wrist Events (register 0x0A — shared by gesture + wakeup) ----

    /** Which arm the device is worn on — affects gesture direction sign. */
    enum class WristArm(val raw: Int) { LEFT(0), RIGHT(1) }

    /** BMI270 wrist-gesture classifications (see Bosch BMI270 datasheet). */
    enum class WristGestureCode(val raw: Int) {
        /** Gesture did not match any known classification. */
        UNKNOWN(0),

        /** Arm pushed downward from a raised position. */
        PUSH_ARM_DOWN(1),

        /** Wrist pivoted up from a horizontal position. */
        PIVOT_UP(2),

        /** Quick shake / jiggle of the wrist. */
        SHAKE(3),   // aka "jiggle"

        /** Arm flicked inward (toward the body). */
        ARM_FLICK_IN(4),

        /** Arm flicked outward (away from the body). */
        ARM_FLICK_OUT(5),
    }

    /**
     * Distinguishes a wrist-wakeup notification from a wrist-gesture
     * notification on the shared 0x0A register. Encoded in the low 2 bits of
     * the payload byte.
     */
    enum class WristEventKind(val raw: Int) {
        /** Wrist-wakeup event — user raised the wrist into the viewing position. */
        WAKEUP(0),

        /** Wrist-gesture event — see [WristGestureCode] for the specific gesture. */
        GESTURE(1),
    }

    /** A single wrist-event notification. For [WristEventKind.WAKEUP], [gestureCode] is UNKNOWN. */
    data class WristEvent(
        /** Whether this is a wakeup or a recognized gesture. */
        val kind: WristEventKind,
        /** Specific gesture code (only meaningful when `kind == GESTURE`). */
        val gestureCode: WristGestureCode,
    )

    /** Subscribe register for wrist gesture + wakeup events. */
    const val WRIST_EVENT_REGISTER: Int = 0x0A

    /**
     * Parse a wrist event notification `[0x03, 0x0A, byte]`.
     * C++ datainterpreter: `type = b & 0x03; code = b >> 2`.
     */
    fun parseWristEvent(packet: ByteArray): WristEvent {
        if (packet.size < 3) throw MetaWearException.OperationFailed(
            "Wrist event packet too short: ${packet.size} bytes"
        )
        val b = packet[2].toInt() and 0xFF
        val kind = WristEventKind.entries.firstOrNull { it.raw == b and 0x03 } ?: WristEventKind.WAKEUP
        val code = WristGestureCode.entries.firstOrNull { it.raw == b shr 2 } ?: WristGestureCode.UNKNOWN
        return WristEvent(kind = kind, gestureCode = code)
    }

    // ---- Wrist Gesture (FEATURE_CONFIG index 8) ----

    /**
     * Writes wrist-gesture parameters to FEATURE_CONFIG. 11-byte command.
     *
     * Bitmap layout (8 bytes after index):
     * ```
     * byte0: out_conf(4) | wearable_arm(1) | enable(3)   → 0x10 if right, 0x00 if left
     * byte1: padding
     * byte2-3: min_flick_peak     (UInt16 LE)
     * byte4-5: min_flick_samples  (UInt16 LE)
     * byte6-7: max_duration       (UInt16 LE)
     * ```
     *
     * Defaults match the C++ SDK.
     */
    class ConfigureWristGesture(
        /** Wrist the device is worn on — toggles the armside bit in the config bitmap. */
        val arm: WristArm = WristArm.LEFT,
        /** Minimum flick peak threshold (`min_flick_peak`). */
        val peak: Int = 0x0332,
        /** Minimum samples in a flick (`min_flick_samples`). */
        val samples: Int = 0x0050,
        /** Maximum total gesture duration in samples (`max_duration`). */
        val duration: Int = 0x0064,
    ) : Command {
        override val commandData: ByteArray
            get() {
                val armByte = if (arm == WristArm.RIGHT) 0x10 else 0x00
                return Packet.command(
                    Module.ACCELEROMETER, 0x08,
                    0x08,                                        // FEATURE_CONFIG index
                    armByte, 0x00,                               // armside + padding
                    peak and 0xFF, (peak shr 8) and 0xFF,
                    samples and 0xFF, (samples shr 8) and 0xFF,
                    duration and 0xFF, (duration shr 8) and 0xFF,
                )
            }
    }

    /** Enables wrist gesture (bit 0x10). */
    class EnableWristGesture : CommandSequence {
        /** `[0x03, 0x07, 0x10, 0x00]` */
        val interruptEnableCommand: ByteArray
            get() = Packet.command(Module.ACCELEROMETER, 0x07, 0x10, 0x00)

        /** `[0x03, 0x06, 0x10, 0x00]` */
        val featureEnableCommand: ByteArray
            get() = Packet.command(Module.ACCELEROMETER, 0x06, 0x10, 0x00)

        override val commands: List<ByteArray>
            get() = listOf(interruptEnableCommand, featureEnableCommand)
    }

    /** Disables wrist gesture (bit 0x10). */
    class DisableWristGesture : CommandSequence {
        /** `[0x03, 0x07, 0x00, 0x10]` */
        val interruptDisableCommand: ByteArray
            get() = Packet.command(Module.ACCELEROMETER, 0x07, 0x00, 0x10)

        /** `[0x03, 0x06, 0x00, 0x10]` */
        val featureDisableCommand: ByteArray
            get() = Packet.command(Module.ACCELEROMETER, 0x06, 0x00, 0x10)

        override val commands: List<ByteArray>
            get() = listOf(interruptDisableCommand, featureDisableCommand)
    }

    // ---- Wrist Wakeup (FEATURE_CONFIG index 9) ----

    /**
     * Writes wrist-wakeup parameters to FEATURE_CONFIG. 15-byte command.
     *
     * Bitmap is 12 bytes = six little-endian UInt16s in this order:
     * `min_angle_focus`, `min_angle_non_focus`, `max_tilt_lr`, `max_tilt_ll`,
     * `max_tilt_pd`, `max_tilt_pu`.
     *
     * Default values taken from the C++ struct initializer:
     * `0xA8 0x05 0xEE 0x06 0x00 0x04 0xBC 0x02 0xB3 0x00 0x85 0x07`.
     */
    class ConfigureWristWakeup(
        /** Minimum angle the device must reach in the focus position. */
        val minAngleFocus: Int = 0x05A8,
        /** Minimum angle from the non-focus position before triggering. */
        val minAngleNonFocus: Int = 0x06EE,
        /** Maximum tilt allowed while rolling right. */
        val maxTiltLR: Int = 0x0400,
        /** Maximum tilt allowed while rolling left. */
        val maxTiltLL: Int = 0x02BC,
        /** Maximum tilt allowed while pitched down. */
        val maxTiltPD: Int = 0x00B3,
        /** Maximum tilt allowed while pitched up. */
        val maxTiltPU: Int = 0x0785,
    ) : Command {
        override val commandData: ByteArray
            get() = Packet.command(
                Module.ACCELEROMETER, 0x08,
                0x09,                                            // FEATURE_CONFIG index
                minAngleFocus and 0xFF, (minAngleFocus shr 8) and 0xFF,
                minAngleNonFocus and 0xFF, (minAngleNonFocus shr 8) and 0xFF,
                maxTiltLR and 0xFF, (maxTiltLR shr 8) and 0xFF,
                maxTiltLL and 0xFF, (maxTiltLL shr 8) and 0xFF,
                maxTiltPD and 0xFF, (maxTiltPD shr 8) and 0xFF,
                maxTiltPU and 0xFF, (maxTiltPU shr 8) and 0xFF,
            )
    }

    /** Enables wrist wakeup (bit 0x08). */
    class EnableWristWakeup : CommandSequence {
        /** `[0x03, 0x07, 0x08, 0x00]` */
        val interruptEnableCommand: ByteArray
            get() = Packet.command(Module.ACCELEROMETER, 0x07, 0x08, 0x00)

        /** `[0x03, 0x06, 0x08, 0x00]` */
        val featureEnableCommand: ByteArray
            get() = Packet.command(Module.ACCELEROMETER, 0x06, 0x08, 0x00)

        override val commands: List<ByteArray>
            get() = listOf(interruptEnableCommand, featureEnableCommand)
    }

    /** Disables wrist wakeup (bit 0x08). */
    class DisableWristWakeup : CommandSequence {
        /** `[0x03, 0x07, 0x00, 0x08]` */
        val interruptDisableCommand: ByteArray
            get() = Packet.command(Module.ACCELEROMETER, 0x07, 0x00, 0x08)

        /** `[0x03, 0x06, 0x00, 0x08]` */
        val featureDisableCommand: ByteArray
            get() = Packet.command(Module.ACCELEROMETER, 0x06, 0x00, 0x08)

        override val commands: List<ByteArray>
            get() = listOf(interruptDisableCommand, featureDisableCommand)
    }

    // ---- No-motion (FEATURE_CONFIG index 2) ----
    //
    // Shares register 0x09 (MOTION_INTERRUPT) with any-motion and sig-motion.
    // The feature-enable bit (0x20) distinguishes no-motion from any-motion (0x40).

    /**
     * Writes no-motion parameters to FEATURE_CONFIG. 7-byte command.
     *
     * Bitmap layout (4 bytes after index):
     * ```
     * byte0: duration_lo (bits 0-7 of 13-bit duration)
     * byte1: duration_hi (bits 8-12) | select_x(bit5) | select_y(bit6) | select_z(bit7)
     * byte2: threshold_lo
     * byte3: threshold_hi (bits 0-2 of 11-bit threshold) | pad(bits 3-7)
     * ```
     *
     * @param duration Sustained-quiet duration in samples (≤ 0x1FFF). Default 5.
     * @param threshold Motion threshold in 0.48 mg/LSB units (≤ 0x7FF). Default 0xAA.
     * @throws MetaWearException.OperationFailed if [duration] or [threshold]
     *   exceed the bit-widths the no-motion register allows.
     */
    class ConfigureNoMotion(
        val duration: Int = 5,
        val threshold: Int = 0xAA,
        /** Whether the X axis is included in the no-motion check. */
        val selectX: Boolean = true,
        /** Whether the Y axis is included in the no-motion check. */
        val selectY: Boolean = true,
        /** Whether the Z axis is included in the no-motion check. */
        val selectZ: Boolean = true,
    ) : Command {
        init {
            if (duration > 0x1FFF || duration < 0) throw MetaWearException.OperationFailed(
                "no-motion duration must fit in 13 bits (0...0x1FFF); got $duration"
            )
            if (threshold > 0x07FF || threshold < 0) throw MetaWearException.OperationFailed(
                "no-motion threshold must fit in 11 bits (0...0x7FF); got $threshold"
            )
        }

        override val commandData: ByteArray
            get() {
                val d0 = duration and 0xFF
                var d1 = (duration shr 8) and 0x1F
                if (selectX) d1 = d1 or (1 shl 5)
                if (selectY) d1 = d1 or (1 shl 6)
                if (selectZ) d1 = d1 or (1 shl 7)
                val t0 = threshold and 0xFF
                val t1 = (threshold shr 8) and 0x07
                return Packet.command(Module.ACCELEROMETER, 0x08, 0x02, d0, d1, t0, t1)
            }
    }

    /** Enables no-motion detection (bit 0x20). */
    class EnableNoMotion : CommandSequence {
        /** `[0x03, 0x07, 0x20, 0x00]` */
        val interruptEnableCommand: ByteArray
            get() = Packet.command(Module.ACCELEROMETER, 0x07, 0x20, 0x00)

        /** `[0x03, 0x06, 0x20, 0x00]` */
        val featureEnableCommand: ByteArray
            get() = Packet.command(Module.ACCELEROMETER, 0x06, 0x20, 0x00)

        override val commands: List<ByteArray>
            get() = listOf(interruptEnableCommand, featureEnableCommand)
    }

    /** Disables no-motion detection (bit 0x20). */
    class DisableNoMotion : CommandSequence {
        /** `[0x03, 0x07, 0x00, 0x20]` */
        val interruptDisableCommand: ByteArray
            get() = Packet.command(Module.ACCELEROMETER, 0x07, 0x00, 0x20)

        /** `[0x03, 0x06, 0x00, 0x20]` */
        val featureDisableCommand: ByteArray
            get() = Packet.command(Module.ACCELEROMETER, 0x06, 0x00, 0x20)

        override val commands: List<ByteArray>
            get() = listOf(interruptDisableCommand, featureDisableCommand)
    }

    // ---- Significant motion (FEATURE_CONFIG index 3) ----
    //
    // Shares register 0x09 (MOTION_INTERRUPT) with any-motion and no-motion.
    // The feature-enable bit (0x01) distinguishes sig-motion from no-motion
    // (0x20) and any-motion (0x40). Sig-motion fires only on sustained
    // movement (e.g. walking, biking, riding in a vehicle).

    /**
     * Writes significant-motion parameters to FEATURE_CONFIG. 5-byte command.
     *
     * Bitmap layout (2 bytes after the index byte): 16-bit little-endian
     * `blocksize` — the number of accelerometer samples accumulated before the
     * on-chip motion-energy classifier evaluates whether to fire. The firmware
     * default is `250` (≈ 2.5 s at 100 Hz ODR).
     */
    class ConfigureSignificantMotion(
        /** Block size in samples (firmware default 250). */
        val blocksize: Int = 250,
    ) : Command {
        override val commandData: ByteArray
            get() = Packet.command(
                Module.ACCELEROMETER, 0x08,
                0x03, blocksize and 0xFF, (blocksize shr 8) and 0xFF,
            )
    }

    /** Enables significant-motion detection (bit 0x01). */
    class EnableSignificantMotion : CommandSequence {
        /** `[0x03, 0x07, 0x01, 0x00]` */
        val interruptEnableCommand: ByteArray
            get() = Packet.command(Module.ACCELEROMETER, 0x07, 0x01, 0x00)

        /** `[0x03, 0x06, 0x01, 0x00]` */
        val featureEnableCommand: ByteArray
            get() = Packet.command(Module.ACCELEROMETER, 0x06, 0x01, 0x00)

        override val commands: List<ByteArray>
            get() = listOf(interruptEnableCommand, featureEnableCommand)
    }

    /** Disables significant-motion detection (bit 0x01). */
    class DisableSignificantMotion : CommandSequence {
        /** `[0x03, 0x07, 0x00, 0x01]` */
        val interruptDisableCommand: ByteArray
            get() = Packet.command(Module.ACCELEROMETER, 0x07, 0x00, 0x01)

        /** `[0x03, 0x06, 0x00, 0x01]` */
        val featureDisableCommand: ByteArray
            get() = Packet.command(Module.ACCELEROMETER, 0x06, 0x00, 0x01)

        override val commands: List<ByteArray>
            get() = listOf(interruptDisableCommand, featureDisableCommand)
    }

    // ---- FIFO Downsampling (register 0x11) ----

    /**
     * Configures BMI270 FIFO downsampling. Single config byte:
     * ```
     * bit 0-2: gyroOrdinal  (gyro downsample = ODR / 2^n, n = 0…7)
     * bit 3:   gyroFilterData (1 = filtered, 0 = unfiltered)
     * bit 4-6: accOrdinal
     * bit 7:   accFilterData
     * ```
     *
     * Register 0x11 = DOWNSAMPLING on BMI270. (The same raw value is
     * ORIENT_INTERRUPT on the BMI160 — see [AccelerometerBosch].)
     *
     * @param gyroOrdinal Gyro downsample exponent — actual factor is `2^gyroOrdinal` (0…7). Default 0.
     * @param gyroFilterData `true` to write filtered gyro samples into the FIFO. Default `false`.
     * @param accOrdinal Accelerometer downsample exponent (0…7). Default 0.
     * @param accFilterData `true` to write filtered accelerometer samples into the FIFO. Default `false`.
     * @throws MetaWearException.OperationFailed if either downsample ordinal
     *   exceeds the 3-bit field the FIFO-config register allows.
     */
    class SetDownsampling(
        val gyroOrdinal: Int = 0,
        val gyroFilterData: Boolean = false,
        val accOrdinal: Int = 0,
        val accFilterData: Boolean = false,
    ) : Command {
        init {
            if (gyroOrdinal > 7 || gyroOrdinal < 0) throw MetaWearException.OperationFailed(
                "gyroOrdinal must fit in 3 bits (0...7); got $gyroOrdinal"
            )
            if (accOrdinal > 7 || accOrdinal < 0) throw MetaWearException.OperationFailed(
                "accOrdinal must fit in 3 bits (0...7); got $accOrdinal"
            )
        }

        override val commandData: ByteArray
            get() {
                var b = (gyroOrdinal and 0x07) or ((accOrdinal and 0x07) shl 4)
                if (gyroFilterData) b = b or (1 shl 3)
                if (accFilterData) b = b or (1 shl 7)
                return Packet.command(Module.ACCELEROMETER, 0x11, b)
            }
    }
}
