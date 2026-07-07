package com.mbientlab.metawear.sensor

import com.mbientlab.metawear.model.CartesianFloat
import com.mbientlab.metawear.model.MetaWearException
import com.mbientlab.metawear.protocol.Command
import com.mbientlab.metawear.protocol.Loggable
import com.mbientlab.metawear.protocol.LogChunk
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.protocol.Packet
import com.mbientlab.metawear.protocol.PacketParser
import kotlin.math.abs
import kotlin.math.roundToInt

// The non-core accelerometer surface: the Bosch-specific gesture-detection
// namespace (`AccelerometerBosch`), the BMI160 step counter / detector
// (`AccelerometerBmi160Steps`), and the type-erased accelerometer
// (`Accelerometer`). The core AccelerometerBmi160 / AccelerometerBmi270
// classes live in Accelerometer.kt.
//
// Accelerometer register opcodes used here (module 0x03), following the C++
// SDK headers (`AccelerometerBosch.h`, `AccelerometerBmi160Register.h`):
//
//   0x09 BMI160: ANY_MOTION_INTERRUPT_ENABLE. BMI270: MOTION_INTERRUPT.
//   0x0A BMI160: ANY_MOTION_CONFIG (threshold + count).
//   0x0B BMI160: ANY_MOTION_INTERRUPT (notification).
//   0x0C BMI160: TAP_INTERRUPT_ENABLE.
//   0x0D BMI160: TAP_CONFIG (timing + threshold).
//   0x0E BMI160: TAP_INTERRUPT (notification).
//   0x0F BMI160: ORIENT_INTERRUPT_ENABLE.
//   0x11 BMI160: ORIENT_INTERRUPT (notification).
//   0x17 BMI160: STEP_DETECTOR_INTERRUPT_EN.
//   0x18 BMI160: STEP_DETECTOR_CONFIG (sensitivity mode + counter enable).
//   0x19 BMI160: STEP_DETECTOR_INTERRUPT (subscribe / notification).
//   0x1A BMI160: STEP_COUNTER_DATA (one-shot read).

/**
 * Orientation detection, any-motion detection, and tap detection for Bosch
 * accelerometers (registers 0x09–0x11).
 */
object AccelerometerBosch {

    /** Distinguishes BMI160 from BMI270 for commands that differ in payload length. */
    enum class ChipVariant {
        /** Bosch BMI160 IMU (impl id 1). */
        BMI160,

        /** Bosch BMI270 IMU (impl id 4). */
        BMI270,
    }

    // ---- Orientation detection ----

    /**
     * The eight board orientations reported by interrupt register 0x11.
     * Parse index = `(responseByte >> 1) & 0x07`.
     */
    enum class SensorOrientation(val raw: Int) {
        FACE_UP_PORTRAIT_UPRIGHT(0),
        FACE_UP_PORTRAIT_UPSIDE_DOWN(1),
        FACE_UP_LANDSCAPE_LEFT(2),
        FACE_UP_LANDSCAPE_RIGHT(3),
        FACE_DOWN_PORTRAIT_UPRIGHT(4),
        FACE_DOWN_PORTRAIT_UPSIDE_DOWN(5),
        FACE_DOWN_LANDSCAPE_LEFT(6),
        FACE_DOWN_LANDSCAPE_RIGHT(7),
    }

    /**
     * Enable orientation-change interrupts (register 0x0F, `[0x01, 0x00]`).
     *
     * Orientation detection is BMI160-specific — the BMI270 has no equivalent
     * feature. Constructing this command with [ChipVariant.BMI270] throws
     * [MetaWearException.OperationFailed] so the wrong-chip mistake fails fast
     * instead of producing a silent stream.
     */
    class EnableOrientation(chip: ChipVariant) : Command {
        init {
            if (chip != ChipVariant.BMI160) throw MetaWearException.OperationFailed(
                "Orientation requires a BMI160 module, which this device lacks."
            )
        }

        override val commandData: ByteArray
            get() = Packet.command(Module.ACCELEROMETER, 0x0F, 0x01, 0x00)
    }

    /**
     * Disable orientation-change interrupts (register 0x0F, `[0x00, 0x01]`).
     *
     * Unguarded by chip variant: writing the disable bits on a BMI270 is a
     * harmless no-op (the register isn't wired to a feature there), so callers
     * can tear down without having to remember the chip variant.
     */
    class DisableOrientation : Command {
        override val commandData: ByteArray
            get() = Packet.command(Module.ACCELEROMETER, 0x0F, 0x00, 0x01)
    }

    /** Parse an orientation notification packet `[0x03, 0x11, byte]`. */
    fun parseOrientation(packet: ByteArray): SensorOrientation {
        if (packet.size < 3) throw MetaWearException.OperationFailed(
            "Orientation packet too short: ${packet.size} bytes"
        )
        val index = ((packet[2].toInt() and 0xFF) shr 1) and 0x07
        return SensorOrientation.entries.getOrNull(index)
            ?: throw MetaWearException.OperationFailed("Unknown orientation index: $index")
    }

    // ---- Any-motion detection ----

    /** Any-motion event decoded from notification register 0x0B. */
    data class AnyMotionEvent(
        /** `true` = positive direction, `false` = negative direction. */
        val isPositive: Boolean,
        /** `true` if motion was detected on the X axis. */
        val xAxisActive: Boolean,
        /** `true` if motion was detected on the Y axis. */
        val yAxisActive: Boolean,
        /** `true` if motion was detected on the Z axis. */
        val zAxisActive: Boolean,
    )

    /**
     * Write the any-motion configuration to register 0x0A.
     *
     * The encoded payload length differs between chips (BMI160 appends an
     * extra no-motion byte), so the [chip] variant must be supplied.
     *
     * @param chip IMU chip variant — controls payload length.
     * @param count Consecutive over-threshold samples required before the interrupt fires (1–4). Default 4.
     * @param thresholdG Detection threshold in g. Default 0.75 g.
     * @param rangeG The accelerometer's currently-configured range in g. Default 8 g.
     * @param noMotionThreshold Raw firmware byte for the paired no-motion threshold. Default 0x14.
     */
    class ConfigureAnyMotion(
        val chip: ChipVariant,
        val count: Int = 4,
        val thresholdG: Float = 0.75f,
        val rangeG: Float = 8.0f,
        val noMotionThreshold: Int = 0x14,
    ) : Command {
        override val commandData: ByteArray
            get() {
                val countByte = (count - 1).coerceIn(0, 255)
                // Resolution: (raw + 1) * (rangeG / 512) g per LSB  ⟹  raw = round(thresholdG * 512 / rangeG) − 1
                val thresholdByte = ((thresholdG * 512.0f / rangeG).roundToInt() - 1).coerceIn(0, 255)
                val payload = mutableListOf(countByte, thresholdByte, noMotionThreshold)
                if (chip == ChipVariant.BMI160) payload.add(noMotionThreshold)  // BMI160 needs an extra no-motion byte
                return Packet.command(Module.ACCELEROMETER, 0x0A, *payload.toIntArray())
            }
    }

    /** Enable any-motion detection on all three axes (register 0x09, `[0x07, 0x00]`). */
    class EnableAnyMotion : Command {
        override val commandData: ByteArray
            get() = Packet.command(Module.ACCELEROMETER, 0x09, 0x07, 0x00)
    }

    /** Disable any-motion detection (register 0x09, `[0x00, 0x7F]`). */
    class DisableAnyMotion : Command {
        override val commandData: ByteArray
            get() = Packet.command(Module.ACCELEROMETER, 0x09, 0x00, 0x7F)
    }

    /** Parse an any-motion notification packet `[0x03, 0x0B, byte]`. */
    fun parseAnyMotion(packet: ByteArray): AnyMotionEvent {
        if (packet.size < 3) throw MetaWearException.OperationFailed(
            "Any-motion packet too short: ${packet.size} bytes"
        )
        val b = packet[2].toInt() and 0xFF
        // bit 6 = 0 → positive direction; bit 6 = 1 → negative direction
        // bit 5 = z axis, bit 4 = y axis, bit 3 = x axis
        return AnyMotionEvent(
            isPositive = (b and 0x40) == 0,
            xAxisActive = (b shr 3) and 1 == 1,
            yAxisActive = (b shr 4) and 1 == 1,
            zAxisActive = (b shr 5) and 1 == 1,
        )
    }

    // ---- Tap detection ----

    /** Tap shock duration: how long a tap impulse can last. */
    enum class TapShockTime(val raw: Int) { MS50(0), MS75(1) }

    /** Quiet time after a tap before another tap can be detected. */
    enum class TapQuietTime(val raw: Int) { MS30(0), MS20(1) }

    /** Time window in which a second tap must occur for a double-tap to register. */
    enum class DoubleTapWindow(val raw: Int) {
        MS50(0), MS100(1), MS150(2), MS200(3), MS250(4), MS375(5), MS500(6), MS700(7)
    }

    /** Whether the fired interrupt was a single or double tap. */
    enum class TapType(val raw: Int) {
        /** Two taps occurred within the configured double-tap window. */
        DOUBLE(1),

        /** A single tap with no second tap inside the window. */
        SINGLE(2),
    }

    /** Tap event decoded from notification register 0x0E. */
    data class TapEvent(
        /** Single-tap or double-tap classification. */
        val type: TapType,
        /** `true` = tap in the positive axis direction, `false` = negative. */
        val isPositive: Boolean,
    )

    /**
     * Write tap-detection configuration to register 0x0D.
     *
     * Byte 0 (INT_TAP[0]): bit7 = shock, bit6 = quiet, bits[3:0] = double-tap window.
     * Byte 1 (INT_TAP[1]): tap_th = round(threshold_g * 32 / range_g), max 31 (5-bit field).
     */
    class ConfigureTap(
        val shockTime: TapShockTime = TapShockTime.MS50,
        val quietTime: TapQuietTime = TapQuietTime.MS30,
        val doubleTapWindow: DoubleTapWindow = DoubleTapWindow.MS250,
        /** Detection threshold in g (capped at the chip's 5-bit `tap_th` field). */
        val thresholdG: Float,
        /** Current accelerometer full-scale range in g. */
        val rangeG: Float,
    ) : Command {
        override val commandData: ByteArray
            get() {
                val timingByte = (shockTime.raw shl 7) or (quietTime.raw shl 6) or doubleTapWindow.raw
                val tapTh = (thresholdG * 32.0f / rangeG).roundToInt().coerceIn(0, 31)
                return Packet.command(Module.ACCELEROMETER, 0x0D, timingByte, tapTh)
            }
    }

    /**
     * Enable single-tap and/or double-tap detection (register 0x0C).
     * Enable byte: bit 1 = single-tap, bit 0 = double-tap.
     */
    class EnableTap(val single: Boolean = true, val double: Boolean = false) : Command {
        override val commandData: ByteArray
            get() {
                val enableByte = (if (single) 0x02 else 0) or (if (double) 0x01 else 0)
                return Packet.command(Module.ACCELEROMETER, 0x0C, enableByte, 0x00)
            }
    }

    /** Disable both single- and double-tap detection (register 0x0C, `[0x00, 0x03]`). */
    class DisableTap : Command {
        override val commandData: ByteArray
            get() = Packet.command(Module.ACCELEROMETER, 0x0C, 0x00, 0x03)
    }

    /** Parse a tap notification packet `[0x03, 0x0E, byte]`. */
    fun parseTap(packet: ByteArray): TapEvent {
        if (packet.size < 3) throw MetaWearException.OperationFailed(
            "Tap packet too short: ${packet.size} bytes"
        )
        val b = packet[2].toInt() and 0xFF
        val type = TapType.entries.firstOrNull { it.raw == b and 0x03 }
            ?: throw MetaWearException.OperationFailed("Unknown tap type in byte: 0x${b.toString(16)}")
        // tap_sign bit 5: 0 = positive direction, 1 = negative direction
        return TapEvent(type = type, isPositive = (b shr 5) and 1 == 0)
    }
}

/**
 * On-chip step counter and step detector commands for the BMI160 IMU.
 *
 * The step counter accumulates total steps in firmware (readable on demand);
 * the step detector fires an interrupt for each step as it occurs. Both share
 * the same on-chip filter — typically only one is enabled at a time.
 *
 * Byte layout for set_mode (NORMAL):
 *   set_mode(NORMAL) → uint16 = 0x0315 → bytes [0x15, 0x03]
 *   enable_step_counter → sets step_cnt_en bit → byte1 |= 0x08 → [0x15, 0x0B]
 *   write_step_counter_config → [0x03, 0x18, 0x15, 0x0B]
 */
object AccelerometerBmi160Steps {

    // ---- Step Counter ----

    /**
     * BMI160 step counter sensitivity mode. The two bytes are the combined
     * mode + step_cnt_en config written to register 0x18:
     *   byte 0: steptime_min | min_threshold | alpha (mode-specific)
     *   byte 1: min_step_buf | step_cnt_en=1 | padding (mode-specific, always enabled)
     */
    enum class StepCounterMode(val byte0: Int, val byte1: Int) {
        /** Balanced between false positives and negatives (recommended). */
        NORMAL(0x15, 0x0B),

        /** Fewer false negatives; may have more false positives. */
        SENSITIVE(0x2D, 0x08),

        /** Fewer false positives; may have more false negatives. */
        ROBUST(0x1D, 0x0F),
    }

    /**
     * Writes the step counter mode + enable to register 0x18. Equivalent to:
     * set_step_counter_mode + enable_step_counter + write_step_counter_config.
     */
    class ConfigureStepCounter(val mode: StepCounterMode = StepCounterMode.NORMAL) : Command {
        override val commandData: ByteArray
            get() = Packet.command(Module.ACCELEROMETER, 0x18, mode.byte0, mode.byte1)
    }

    /**
     * Read the step count on demand. Sends `[0x03, 0x9A]` (register 0x1A with
     * bit 7 set). Call this after subscribing (a response handler is registered).
     */
    class ReadStepCounter : Command {
        override val commandData: ByteArray
            get() = Packet.read(Module.ACCELEROMETER, 0x1A)
    }

    /**
     * Silent read — no response forwarded to subscriber. Sends `[0x03, 0xDA]`
     * (0xDA = 0x1A | 0x80 | 0x40). Use when reading without an active subscriber.
     */
    class ReadStepCounterSilent : Command {
        override val commandData: ByteArray
            get() = Packet.command(Module.ACCELEROMETER, 0x1A or 0x80 or 0x40)
    }

    /**
     * Parse a step count response packet `[0x03, 0x9A, low, high]`.
     * The count is a little-endian UInt16 in bytes 2–3.
     */
    fun parseStepCount(packet: ByteArray): Int {
        if (packet.size < 4) throw MetaWearException.OperationFailed(
            "Step counter packet too short: ${packet.size} bytes"
        )
        return PacketParser.parseUInt16LE(packet, 2)
    }

    // ---- Step Detector ----

    /** Enable the step detector interrupt on register 0x17. Sends `[0x03, 0x17, 0x01, 0x00]`. */
    class EnableStepDetector : Command {
        override val commandData: ByteArray
            get() = Packet.command(Module.ACCELEROMETER, 0x17, 0x01, 0x00)
    }

    /** Disable the step detector interrupt on register 0x17. Sends `[0x03, 0x17, 0x00, 0x01]`. */
    class DisableStepDetector : Command {
        override val commandData: ByteArray
            get() = Packet.command(Module.ACCELEROMETER, 0x17, 0x00, 0x01)
    }

    /**
     * Subscribe to step detection events: send `[0x03, 0x19, 0x01]` to the
     * board. This register byte is used when subscribing to the stream.
     */
    const val STEP_DETECTOR_REGISTER: Int = 0x19

    /** Parse a step detector notification `[0x03, 0x19, byte]` → 1 = step detected. */
    fun parseStepDetection(packet: ByteArray): Int {
        if (packet.size < 3) throw MetaWearException.OperationFailed(
            "Step detection packet too short: ${packet.size} bytes"
        )
        return packet[2].toInt() and 0xFF
    }
}

/**
 * Type-erased accelerometer that wraps whichever Bosch IMU variant the
 * connected MetaWear actually has. Use this when the chip is determined at
 * runtime (typically from the module-info handshake) rather than known
 * statically.
 *
 * Implements [Loggable], forwarding `parseSample`, `configureCommands`,
 * `enableCommand`, etc. to the underlying chip-specific implementation. This
 * mirrors the C++ generic API (`mbl_mw_acc_start`,
 * `mbl_mw_acc_enable_acceleration_sampling`, etc.) which dispatches to the
 * correct chip implementation internally.
 */
sealed class Accelerometer : Loggable<CartesianFloat> {

    /** The board has a BMI160 IMU (impl id 1). */
    data class Bmi160(val sensor: AccelerometerBmi160) : Accelerometer()

    /** The board has a BMI270 IMU (impl id 4). */
    data class Bmi270(val sensor: AccelerometerBmi270) : Accelerometer()

    private val chip: Loggable<CartesianFloat>
        get() = when (this) {
            is Bmi160 -> sensor
            is Bmi270 -> sensor
        }

    // ---- Loggable forwarding ----

    override val module: Module get() = Module.ACCELEROMETER
    override val dataRegister: Int get() = 0x04   // same on both chips
    override val packedDataRegister: Int? get() = chip.packedDataRegister
    override val configureCommands: List<ByteArray> get() = chip.configureCommands
    override val enableCommand: ByteArray get() = chip.enableCommand
    override val startCommand: ByteArray get() = chip.startCommand
    override val stopCommand: ByteArray get() = chip.stopCommand
    override val disableCommand: ByteArray get() = chip.disableCommand
    override val loggerKey: String get() = "acceleration"
    override val logDataChunks: List<LogChunk> get() = chip.logDataChunks

    override fun parseSample(packet: ByteArray): CartesianFloat = chip.parseSample(packet)

    override fun parsePackedSamples(packet: ByteArray): List<CartesianFloat> =
        chip.parsePackedSamples(packet)

    override fun parseLogSample(data: ByteArray): CartesianFloat = chip.parseLogSample(data)

    // ---- Snapped configuration ----

    /**
     * The actual ODR after snapping to the nearest supported value, in Hz.
     * Mirrors the C++ `mbl_mw_acc_set_odr` return value.
     */
    val odrHz: Double
        get() = when (this) {
            is Bmi160 -> sensor.odr.hz
            is Bmi270 -> sensor.odr.hz
        }

    /**
     * The actual range after snapping to the nearest supported value, in g.
     * Mirrors the C++ `mbl_mw_acc_set_range` return value.
     */
    val rangeG: Float
        get() = when (this) {
            is Bmi160 -> sensor.range.rangeG
            is Bmi270 -> sensor.range.rangeG
        }

    /**
     * Returns a new sensor with the ODR snapped to the nearest supported value.
     * Equivalent to C++ `mbl_mw_acc_set_odr` — no BLE write occurs; the config
     * is applied to the board on the next stream / log start.
     */
    fun withOdr(odrHz: Double): Accelerometer = when (this) {
        is Bmi160 -> Bmi160(AccelerometerBmi160(nearestOdrBmi160(odrHz), sensor.range))
        is Bmi270 -> Bmi270(AccelerometerBmi270(nearestOdrBmi270(odrHz), sensor.range))
    }

    /**
     * Returns a new sensor with the range snapped to the nearest supported value.
     * Equivalent to C++ `mbl_mw_acc_set_range` — no BLE write occurs; the config
     * is applied to the board on the next stream / log start.
     */
    fun withRange(rangeG: Float): Accelerometer = when (this) {
        is Bmi160 -> Bmi160(AccelerometerBmi160(sensor.odr, nearestRangeBmi160(rangeG)))
        is Bmi270 -> Bmi270(AccelerometerBmi270(sensor.odr, nearestRangeBmi270(rangeG)))
    }

    companion object {
        /**
         * Build a type-erased accelerometer for the given chip impl id, snapping
         * ODR and range to the nearest values supported by the chip.
         *
         * @param impl Module info impl id (1 = BMI160, 4 = BMI270). Other values return `null`.
         * @param odrHz Desired output data rate in Hz. Default 100.
         * @param rangeG Desired full-scale range in g. Default 2.
         */
        fun make(impl: Int, odrHz: Double = 100.0, rangeG: Float = 2f): Accelerometer? =
            when (impl) {
                1 -> Bmi160(AccelerometerBmi160(nearestOdrBmi160(odrHz), nearestRangeBmi160(rangeG)))
                4 -> Bmi270(AccelerometerBmi270(nearestOdrBmi270(odrHz), nearestRangeBmi270(rangeG)))
                else -> null
            }

        private fun nearestOdrBmi160(odrHz: Double): AccelerometerBmi160.Odr =
            AccelerometerBmi160.Odr.entries.minByOrNull { abs(it.hz - odrHz) }!!

        private fun nearestRangeBmi160(rangeG: Float): AccelerometerBmi160.Range =
            AccelerometerBmi160.Range.entries.minByOrNull { abs(it.rangeG - rangeG) }!!

        private fun nearestOdrBmi270(odrHz: Double): AccelerometerBmi270.Odr =
            AccelerometerBmi270.Odr.entries.minByOrNull { abs(it.hz - odrHz) }!!

        private fun nearestRangeBmi270(rangeG: Float): AccelerometerBmi270.Range =
            AccelerometerBmi270.Range.entries.minByOrNull { abs(it.rangeG - rangeG) }!!
    }
}
